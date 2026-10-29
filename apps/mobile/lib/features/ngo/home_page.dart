import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../notifications/notification_pages.dart';
import 'ngo_api.dart';

/// DASH-001: home dashboard. Shows verification state first, then subscription and quick actions.
class NgoHomePage extends ConsumerWidget {
  const NgoHomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final me = ref.watch(meProvider).value;
    final ngo = ref.watch(currentNgoProvider);
    final sub = ref.watch(subscriptionProvider);
    final membership = ref.watch(currentMembershipProvider);
    // Donations, donors and campaigns need a verified NGO with an operational subscription (trial/active/grace).
    // Staff without SUBSCRIPTION_VIEW cannot read the subscription (null): don't grey out their actions on a guess -
    // the server enforces the subscription rule on every paid operation anyway.
    bool operational(Map<String, dynamic>? n) {
      final s = sub.value;
      final subscriptionOk = s == null || const {'TRIAL', 'ACTIVE', 'GRACE'}.contains(s['status']);
      return n?['verificationStatus'] == 'APPROVED' && subscriptionOk;
    }
    return Scaffold(
      appBar: AppBar(
        title: ngo.maybeWhen(data: (n) => Text('${n?['displayName'] ?? t.appName}'), orElse: () => Text(t.appName)),
        actions: [
          const NotificationBell(),
          IconButton(tooltip: t.settings, icon: const Icon(Icons.settings_outlined), onPressed: () => context.push('/settings')),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(currentNgoProvider);
          ref.invalidate(subscriptionProvider);
          ref.invalidate(unreadCountProvider);
        },
        child: ngo.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(currentNgoProvider)),
          data: (n) => ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            Text(t.hello(me?.name.split(' ').first ?? ''), style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: AppSpacing.md),
            if (n != null) _VerificationBanner(ngo: n),
            sub.maybeWhen(data: (s) => s == null ? const SizedBox.shrink() : _SubscriptionCard(sub: s), orElse: () => const SizedBox.shrink()),
            SectionHeader(t.quickActions),
            GridView.count(
              crossAxisCount: 2,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              mainAxisSpacing: AppSpacing.sm,
              crossAxisSpacing: AppSpacing.sm,
              childAspectRatio: 1.5,
              children: [
                // Each quick action is shown only to members whose role grants it.
                if (membership?.can('DONATION_RECORD') ?? false)
                  ActionTile(label: t.recordDonation, icon: Icons.add_card,
                      disabledHint: t.availableAfterVerification, onTap: operational(n) ? () => context.go('/ngo/donors') : null),
                if (membership?.can('DONOR_MANAGE') ?? false)
                  ActionTile(label: t.addDonor, icon: Icons.person_add_alt, color: AppColors.growth,
                      disabledHint: t.availableAfterVerification, onTap: operational(n) ? () => context.go('/ngo/donors') : null),
                if (membership?.can('STAFF_MANAGE') ?? false)
                  ActionTile(label: t.inviteStaff, icon: Icons.group_add, color: AppColors.professional,
                      onTap: () => context.push('/ngo/staff')),
                if (membership?.can('CAMPAIGN_MANAGE') ?? false)
                  ActionTile(label: t.newCampaign, icon: Icons.campaign, color: AppColors.enterprise,
                      disabledHint: t.availableAfterVerification, onTap: operational(n) ? () => context.push('/ngo/campaigns/new') : null),
              ],
            ),
          ]),
        ),
      ),
    );
  }
}

class _VerificationBanner extends StatelessWidget {
  const _VerificationBanner({required this.ngo});
  final Map<String, dynamic> ngo;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final reg = ngo['registrationStatus'];
    final v = ngo['verificationStatus'];
    if (v == 'APPROVED') return const SizedBox.shrink();
    final (icon, color, title, body, cta) = switch ((reg, v)) {
      ('DRAFT', _) => (Icons.edit_note, AppColors.warning, t.continueRegistration, t.registerNgoSub, true),
      (_, 'PENDING') => (Icons.hourglass_top, AppColors.growth, t.verificationPending, t.verificationPendingBody, false),
      (_, 'INFO_REQUESTED') => (Icons.info_outline, AppColors.warning, t.verificationInfoRequested, '', true),
      _ => (Icons.block, AppColors.error, t.verificationRejected, '', true),
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: AppCard(
        color: color.withValues(alpha: 0.10),
        onTap: cta ? () => context.push('/ngo/register/${ngo['id']}') : null,
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(icon, color: color),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
            if (body.isNotEmpty) ...[const SizedBox(height: 4), Text(body)],
          ])),
          if (cta) const Icon(Icons.chevron_right),
        ]),
      ),
    );
  }
}

class _SubscriptionCard extends StatelessWidget {
  const _SubscriptionCard({required this.sub});
  final Map<String, dynamic> sub;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final status = '${sub['status']}';
    final plan = '${sub['planName']}';
    final color = AppColors.plan(sub['plan'] as String?);
    return AppCard(
      onTap: () => context.push('/ngo/subscription'),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(Icons.workspace_premium, color: color),
          const SizedBox(width: 8),
          Expanded(child: Text('$plan · ${t.subscription}', style: Theme.of(context).textTheme.titleMedium)),
          StatusBadge(status, tone: StatusBadge.toneFor(status)),
        ]),
        const SizedBox(height: AppSpacing.sm),
        Text(status == 'TRIAL' ? t.trialDaysLeft((sub['daysRemaining'] as num).toInt()) : t.renewsOn(formatDate(sub['periodEnd']))),
        if (status == 'TRIAL') ...[
          const SizedBox(height: AppSpacing.sm),
          LinearProgressIndicator(value: 1 - ((sub['daysRemaining'] as num) / 30).clamp(0, 1), color: color,
              backgroundColor: color.withValues(alpha: 0.15), minHeight: 6, borderRadius: BorderRadius.circular(6)),
        ],
      ]),
    );
  }
}
