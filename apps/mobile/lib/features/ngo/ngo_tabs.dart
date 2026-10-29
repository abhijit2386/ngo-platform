import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';

/// Paged list from a tenant-scoped endpoint returning PageResponse {items, ...}.
final _pagedProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, path) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson(path, query: {'size': 50});
  return (r['items'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

class _TenantList extends ConsumerWidget {
  const _TenantList({required this.title, required this.resource, required this.emptyIcon, required this.emptyTitle, required this.tile});
  final String title;
  final String resource;
  final IconData emptyIcon;
  final String emptyTitle;
  final Widget Function(BuildContext, Map<String, dynamic>) tile;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final path = '/api/v1/ngos/$ngoId/$resource';
    final data = ref.watch(_pagedProvider(path));
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(_pagedProvider(path)),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) {
            final err = ApiError.from(e);
            final msg = (err.status == 404 || err.status == 503 || err.isNetwork) ? t.serviceUnavailable : err.message;
            return ListView(children: [ErrorState(message: msg, onRetry: () => ref.invalidate(_pagedProvider(path)))]);
          },
          data: (items) => items.isEmpty
              ? ListView(children: [EmptyState(icon: emptyIcon, title: emptyTitle)])
              : ListView.separated(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  itemCount: items.length,
                  separatorBuilder: (_, __) => const SizedBox(height: AppSpacing.sm),
                  itemBuilder: (c, i) => tile(c, items[i]),
                ),
        ),
      ),
    );
  }
}

/// DONOR-001
class DonorsTab extends StatelessWidget {
  const DonorsTab({super.key});
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return _TenantList(
      title: t.navDonors,
      resource: 'donors',
      emptyIcon: Icons.people_outline,
      emptyTitle: t.navDonors,
      tile: (c, d) => AppCard(
        child: Row(children: [
          CircleAvatar(child: Text('${d['name'] ?? '?'}'[0].toUpperCase())),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${d['name']}', style: const TextStyle(fontWeight: FontWeight.w600)),
            Text('${d['email'] ?? d['phone'] ?? ''}', style: const TextStyle(color: AppColors.textMuted)),
          ])),
          Text(formatInr(d['totalDonated'] ?? 0, whole: true), style: const TextStyle(fontWeight: FontWeight.w700)),
        ]),
      ),
    );
  }
}

/// RPT-001: headline numbers from reporting-service projections.
class ReportsTab extends ConsumerWidget {
  const ReportsTab({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final data = ref.watch(_dashboardProvider(ngoId ?? ''));
    return Scaffold(
      appBar: AppBar(title: Text(t.navReports)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(_dashboardProvider(ngoId ?? '')),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: t.serviceUnavailable, onRetry: () => ref.invalidate(_dashboardProvider(ngoId ?? '')))]),
          data: (d) => GridView.count(
            padding: const EdgeInsets.all(AppSpacing.md),
            crossAxisCount: 2,
            mainAxisSpacing: AppSpacing.sm,
            crossAxisSpacing: AppSpacing.sm,
            childAspectRatio: 1.4,
            children: [
              StatTile(label: t.navDonations, value: formatInr(d['donationsTotal'] ?? 0, whole: true), icon: Icons.volunteer_activism),
              StatTile(label: t.navDonors, value: '${d['donorCount'] ?? 0}', icon: Icons.people, color: AppColors.growth),
              StatTile(label: t.activeCampaigns, value: '${d['activeCampaigns'] ?? 0}', icon: Icons.campaign, color: AppColors.enterprise),
              StatTile(label: t.activeProjects, value: '${d['activeProjects'] ?? 0}', icon: Icons.work_outline, color: AppColors.professional),
            ],
          ),
        ),
      ),
    );
  }
}

final _dashboardProvider = FutureProvider.family<Map<String, dynamic>, String>(
    (ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/dashboard');
});
