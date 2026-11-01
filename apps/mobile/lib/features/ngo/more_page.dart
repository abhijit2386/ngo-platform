import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/session/preferences.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import 'ngo_api.dart';

/// "More" tab: secondary destinations, permission-aware.
class MorePage extends ConsumerWidget {
  const MorePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final m = ref.watch(currentMembershipProvider);
    final me = ref.watch(meProvider).value;
    final memberships = me?.activeMemberships ?? const [];
    Widget item(IconData icon, String label, VoidCallback onTap) =>
        ListTile(leading: Icon(icon, color: AppColors.primary), title: Text(label), trailing: const Icon(Icons.chevron_right), onTap: onTap);
    return Scaffold(
      appBar: AppBar(title: Text(t.navMore)),
      body: ListView(children: [
        item(Icons.apartment, t.ngoProfile, () => context.push('/ngo/profile')),
        if (m?.can('RECEIPT_VIEW') ?? false) item(Icons.receipt_long_outlined, t.rcpTitle, () => context.push('/ngo/receipts')),
        if (m?.can('DOCUMENT_VIEW') ?? false) item(Icons.folder_copy_outlined, t.docTitle, () => context.push('/ngo/documents')),
        if (m?.can('GOVERNANCE_VIEW') ?? false) item(Icons.event_note_outlined, t.meetTitle, () => context.push('/ngo/meetings')),
        if (m?.can('SUBSCRIPTION_VIEW') ?? true) item(Icons.workspace_premium, t.subscription, () => context.push('/ngo/subscription')),
        if (m?.can('DONATION_VIEW') ?? false) item(Icons.payments_outlined, t.onlinePayments, () => context.push('/ngo/payments')),
        if (m?.isAdmin ?? false) item(Icons.fact_check_outlined, t.accToApprove, () => context.push('/ngo/expenses/pending')),
        if (m?.can('EXPENSE_VIEW') ?? false) item(Icons.storefront_outlined, t.accVendors, () => context.push('/ngo/vendors')),
        if (m?.isAdmin ?? false)
          item(Icons.account_balance_wallet_outlined, t.accStaffCash, () => context.push('/ngo/staff-money'))
        else if (m?.can('EXPENSE_RECORD') ?? false)
          item(Icons.account_balance_wallet_outlined, t.accMyAdvances, () => context.push('/ngo/staff-money/me')),
        if (m?.can('STAFF_VIEW') ?? false) item(Icons.groups, t.staffDirectory, () => context.push('/ngo/staff')),
        item(Icons.settings, t.settings, () => context.push('/settings')),
        if (me?.isPlatformSupport ?? false) ...[
          const Divider(),
          item(Icons.admin_panel_settings, t.platformAdmin, () => context.push('/admin/verifications')),
        ],
        if (memberships.length > 1) ...[
          const Divider(),
          for (final other in memberships)
            ListTile(
              leading: Icon(other.ngoId == m?.ngoId ? Icons.radio_button_checked : Icons.radio_button_off),
              title: Text(other.ngoId.substring(0, 8)),
              subtitle: Text(other.role),
              onTap: () {
                ref.read(prefsProvider.notifier).selectNgo(other.ngoId);
                ref.invalidate(currentNgoProvider);
                ref.invalidate(subscriptionProvider);
              },
            ),
        ],
        const Divider(),
        item(Icons.volunteer_activism, t.switchToDonor, () => ref.read(prefsProvider.notifier).setMode('donor')),
      ]),
    );
  }
}

/// NGO-020: private NGO profile summary.
class NgoProfilePage extends ConsumerWidget {
  const NgoProfilePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngo = ref.watch(currentNgoProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t.ngoProfile)),
      body: ngo.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(currentNgoProvider)),
        data: (n) {
          if (n == null) return const SizedBox.shrink();
          final a = n['address'] as Map?;
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            AppCard(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${n['displayName']}', style: Theme.of(context).textTheme.titleLarge),
                Text('${n['legalName']}', style: const TextStyle(color: AppColors.textMuted)),
                const SizedBox(height: 8),
                Wrap(spacing: 8, runSpacing: 8, children: [
                  StatusBadge('${n['verificationStatus']}', tone: StatusBadge.toneFor('${n['verificationStatus']}')),
                  StatusBadge('${n['donationEligibility']}', tone: StatusBadge.toneFor('${n['donationEligibility']}')),
                ]),
                const Divider(height: 24),
                KeyValueRow(t.registrationNumber, n['registrationNumber'] as String?),
                KeyValueRow(t.registrationState, n['registrationState'] as String?),
                KeyValueRow(t.darpanId, n['darpanId'] as String?),
                KeyValueRow(t.pan, n['pan'] as String?),
                KeyValueRow(t.officialEmail, n['officialEmail'] as String?),
                KeyValueRow(t.officialPhone, n['officialPhone'] as String?),
                KeyValueRow(t.address, a == null ? null : '${a['line1']}, ${a['city']}, ${a['state']} ${a['pinCode']}'),
              ]),
            ),
            const SizedBox(height: AppSpacing.md),
            if (n['registrationStatus'] == 'DRAFT' || n['verificationStatus'] == 'INFO_REQUESTED' || n['verificationStatus'] == 'REJECTED')
              FilledButton(onPressed: () => context.push('/ngo/register/${n['id']}'), child: Text(t.continueRegistration)),
          ]);
        },
      ),
    );
  }
}
