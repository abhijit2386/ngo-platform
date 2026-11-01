import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/config/app_config.dart';
import '../../core/session/preferences.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../campaigns/campaign_api.dart' show isTeamMemberOf;
import '../campaigns/campaign_pages.dart';
import '../donations/donation_pages.dart';
import '../profile/avatar.dart';
import '../registration/registration_pages.dart' show causeLabel;
import '../settings/settings_pages.dart';

class _Query {
  const _Query(this.q, this.cause);
  final String q;
  final String? cause;
  @override
  bool operator ==(Object o) => o is _Query && o.q == q && o.cause == cause;
  @override
  int get hashCode => Object.hash(q, cause);
}

final _searchProvider = FutureProvider.family<List<Map<String, dynamic>>, _Query>((ref, q) async {
  final r = await ref.read(apiProvider).getJson('/api/v1/public/ngos', query: {'q': q.q, 'cause': q.cause, 'size': 30});
  return (r['items'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

/// DAPP-001..004: discover verified NGOs.
class DiscoverPage extends ConsumerStatefulWidget {
  const DiscoverPage({super.key});
  @override
  ConsumerState<DiscoverPage> createState() => _DiscoverPageState();
}

class _DiscoverPageState extends ConsumerState<DiscoverPage> {
  final _q = TextEditingController();
  String _query = '';
  String? _cause;
  static const _causes = ['EDUCATION', 'HEALTH', 'CHILD_WELFARE', 'WOMEN_EMPOWERMENT', 'ELDERLY_CARE', 'ENVIRONMENT', 'ANIMAL_WELFARE', 'HUNGER_NUTRITION'];

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final me = ref.watch(meProvider).value;
    final results = ref.watch(_searchProvider(_Query(_query, _cause)));
    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: () async => ref.invalidate(_searchProvider(_Query(_query, _cause))),
          child: CustomScrollView(slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              sliver: SliverList.list(children: [
                Text(t.hello(me?.name.split(' ').first ?? ''), style: const TextStyle(color: AppColors.textMuted)),
                Text(t.discoverTitle, style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: AppSpacing.md),
                TextField(
                  controller: _q,
                  textInputAction: TextInputAction.search,
                  decoration: InputDecoration(hintText: t.searchNgos, prefixIcon: const Icon(Icons.search)),
                  onSubmitted: (v) => setState(() => _query = v.trim()),
                ),
                if (_query.isEmpty && _cause == null) ...[
                  const SizedBox(height: AppSpacing.md),
                  const LiveCampaignsStrip(),
                  const SizedBox(height: AppSpacing.sm),
                  Text(t.verifiedNgos, style: Theme.of(context).textTheme.titleMedium),
                ],
                const SizedBox(height: AppSpacing.sm),
                SizedBox(
                  height: 44,
                  child: ListView(scrollDirection: Axis.horizontal, children: [
                    for (final c in _causes)
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: FilterChip(label: Text(causeLabel(c)), selected: _cause == c,
                            onSelected: (s) => setState(() => _cause = s ? c : null)),
                      ),
                  ]),
                ),
              ]),
            ),
            results.when(
              loading: () => const SliverFillRemaining(child: LoadingView()),
              error: (e, _) => SliverFillRemaining(child: ErrorState(message: ApiError.from(e).message,
                  onRetry: () => ref.invalidate(_searchProvider(_Query(_query, _cause))))),
              data: (list) => list.isEmpty
                  ? SliverFillRemaining(child: EmptyState(icon: Icons.search_off, title: t.noResults, message: t.noResultsHint))
                  : SliverPadding(
                      padding: const EdgeInsets.all(16),
                      sliver: SliverList.separated(
                        itemCount: list.length,
                        separatorBuilder: (_, __) => const SizedBox(height: AppSpacing.sm),
                        itemBuilder: (_, i) => NgoCard(ngo: list[i]),
                      ),
                    ),
            ),
          ]),
        ),
      ),
    );
  }
}

class NgoLogo extends StatelessWidget {
  const NgoLogo({super.key, required this.ngo, this.size = 52});
  final Map<String, dynamic> ngo;
  final double size;
  @override
  Widget build(BuildContext context) {
    final color = ngo['brandColor'] != null ? Color(int.parse('FF${'${ngo['brandColor']}'.substring(1)}', radix: 16)) : AppColors.primary;
    final fallback = Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(14)),
      alignment: Alignment.center,
      child: Text('${ngo['displayName']}'[0], style: TextStyle(color: color, fontSize: size * 0.45, fontWeight: FontWeight.w700)),
    );
    if (ngo['hasLogo'] != true) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: Image.network('${AppConfig.apiBaseUrl}/api/v1/public/ngos/${ngo['id']}/logo', width: size, height: size,
          fit: BoxFit.cover, errorBuilder: (_, __, ___) => fallback),
    );
  }
}

class NgoCard extends StatelessWidget {
  const NgoCard({super.key, required this.ngo});
  final Map<String, dynamic> ngo;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final causes = List<String>.from(ngo['areasOfActivity'] as List? ?? const []);
    return AppCard(
      onTap: () => context.push('/donor/ngo/${ngo['id']}'),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        NgoLogo(ngo: ngo),
        const SizedBox(width: AppSpacing.md),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('${ngo['displayName']}', style: Theme.of(context).textTheme.titleMedium)),
            if (ngo['verified'] == true) Tooltip(message: t.verified, child: const Icon(Icons.verified, color: AppColors.primary, size: 20)),
          ]),
          Text([ngo['city'], ngo['state']].where((e) => e != null).join(', '), style: const TextStyle(color: AppColors.textMuted)),
          const SizedBox(height: 6),
          Wrap(spacing: 6, runSpacing: 4, children: [for (final c in causes.take(3)) StatusBadge(causeLabel(c), tone: BadgeTone.info)]),
        ])),
      ]),
    );
  }
}

/// DAPP-005/006: public profile (no private verification records are exposed).
class PublicNgoPage extends ConsumerWidget {
  const PublicNgoPage({super.key, required this.ngoId});
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngo = ref.watch(_publicNgoProvider(ngoId));
    return Scaffold(
      appBar: AppBar(),
      body: ngo.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_publicNgoProvider(ngoId))),
        data: (n) => ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          Row(children: [
            NgoLogo(ngo: n, size: 72),
            const SizedBox(width: AppSpacing.md),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${n['displayName']}', style: Theme.of(context).textTheme.titleLarge),
              Text([n['city'], n['state']].where((e) => e != null).join(', '), style: const TextStyle(color: AppColors.textMuted)),
            ])),
          ]),
          const SizedBox(height: AppSpacing.md),
          if (n['verified'] == true)
            AppCard(
              color: AppColors.primarySoft,
              child: Row(children: [
                const Icon(Icons.verified, color: AppColors.primary),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: Text('${t.verified} · ${formatDate(n['verifiedSince'])}')),
                if (n['tax80gEligible'] == true) const StatusBadge('80G', tone: BadgeTone.success),
              ]),
            ),
          const SizedBox(height: AppSpacing.md),
          Text('${n['description'] ?? ''}', style: const TextStyle(fontSize: 16, height: 1.5)),
          const SizedBox(height: AppSpacing.md),
          Wrap(spacing: 6, runSpacing: 6, children: [
            for (final c in List<String>.from(n['areasOfActivity'] as List? ?? const [])) StatusBadge(causeLabel(c), tone: BadgeTone.info),
          ]),
          const SizedBox(height: AppSpacing.lg),
          LiveCampaignsStrip(ngoId: ngoId, title: t.navCampaigns),
          const SizedBox(height: AppSpacing.lg),
          // Owner rule (2026-10-29): an NGO's own admins/staff don't see Donate for their NGO.
          if (!isTeamMemberOf(ref.watch(meProvider).value, ngoId))
            FilledButton.icon(
              // Donation checkout lands with donation-payment-service (next build).
              onPressed: n['acceptingDonations'] == true
                  ? () => startDonation(context, ngoId: ngoId, title: '${n['displayName']}')
                  : null,
              icon: const Icon(Icons.volunteer_activism),
              label: Text(t.donate),
            ),
        ]),
      ),
    );
  }
}

final _publicNgoProvider = FutureProvider.family<Map<String, dynamic>, String>(
    (ref, id) => ref.read(apiProvider).getJson('/api/v1/public/ngos/$id'));

/// DAPP-022: followed NGOs (donor-service).
class FollowingPage extends ConsumerWidget {
  const FollowingPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(_followingProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t.navFollowing)),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: t.serviceUnavailable, onRetry: () => ref.invalidate(_followingProvider)),
        data: (list) => list.isEmpty
            ? EmptyState(icon: Icons.favorite_border, title: t.navFollowing, message: t.discoverTitle)
            : ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [for (final n in list) NgoCard(ngo: n)]),
      ),
    );
  }
}

final _followingProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final list = await ref.read(apiProvider).getList('/api/v1/donor/following');
  return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

/// DAPP-024: donor profile & settings entry.
class DonorProfilePage extends ConsumerWidget {
  const DonorProfilePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final me = ref.watch(meProvider).value;
    return Scaffold(
      appBar: AppBar(title: Text(t.navProfile)),
      body: ListView(children: [
        ListTile(
          leading: me == null
              ? const CircleAvatar(radius: 28)
              : UserAvatar(userId: me.userId, name: me.name, avatar: me.avatar, radius: 28, self: true),
          title: Text(me?.name ?? ''),
          subtitle: Text(me?.email ?? ''),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => context.push('/profile'),
        ),
        const Divider(),
        ListTile(leading: const Icon(Icons.receipt_long_outlined), title: Text(t.rcpMine), trailing: const Icon(Icons.chevron_right),
            onTap: () => context.push('/donor/receipts')),
        ListTile(leading: const Icon(Icons.apartment), title: Text(t.switchToNgo),
            onTap: () => ref.read(prefsProvider.notifier).setMode('ngo')),
        if (me?.isPlatformSupport ?? false)
          ListTile(leading: const Icon(Icons.admin_panel_settings), title: Text(t.platformAdmin), onTap: () => context.push('/admin/verifications')),
        ListTile(leading: const Icon(Icons.settings), title: Text(t.settings),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SettingsPage()))),
      ]),
    );
  }
}
