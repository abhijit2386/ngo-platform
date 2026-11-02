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
