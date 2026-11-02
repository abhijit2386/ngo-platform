import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../accounts/accounts_api.dart' show downloadAndOffer;

/// REPORT-001: the Reports tab. Administrators and Finance see NGO-wide reports; everyone else with report access
/// sees "My report" (owner decision 2 Nov 2026). Sections depend on the plan (reporting-service decides).

const _xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
final _iso = DateFormat('yyyy-MM-dd');

/// Selected period; null = current financial year (the server default).
final reportRangeProvider = NotifierProvider<ReportRange, DateTimeRange?>(ReportRange.new);

class ReportRange extends Notifier<DateTimeRange?> {
  @override
  DateTimeRange? build() => null;

  void set(DateTimeRange? r) => state = r;
}

String _query(DateTimeRange? r) => r == null ? '' : '?from=${_iso.format(r.start)}&to=${_iso.format(r.end)}';

final _reportProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, path) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson(path);
});

DateTime _fyStart(DateTime d) => DateTime(d.month >= 4 ? d.year : d.year - 1, 4, 1);

class ReportsTab extends ConsumerWidget {
  const ReportsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider) ?? '';
    final dash = ref.watch(_reportProvider('/api/v1/ngos/$ngoId/dashboard'));
    return Scaffold(
      appBar: AppBar(title: Text(t.navReports)),
      body: dash.when(
        loading: () => const LoadingView(),
        error: (e, _) {
          final err = ApiError.from(e);
          final msg = err.status == 403 ? t.repNoAccess : (err.status == 404 || err.status == 503 || err.isNetwork) ? t.serviceUnavailable : err.message;
          return ListView(children: [ErrorState(message: msg, onRetry: () => ref.invalidate(_reportProvider('/api/v1/ngos/$ngoId/dashboard')))]);
        },
        data: (d) => d['scope'] == 'NGO' ? _NgoReport(ngoId: ngoId) : _MyReport(ngoId: ngoId),
      ),
    );
  }
}

/// Period chip: this financial year, last financial year, this month, or chosen dates.
class _PeriodBar extends ConsumerWidget {
  const _PeriodBar({required this.label});
  final String? label;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    return Align(
      alignment: Alignment.centerLeft,
      child: ActionChip(
        avatar: const Icon(Icons.date_range, size: 18),
        label: Text(label ?? t.repThisFy),
        onPressed: () async {
          final now = DateTime.now();
          final fy = _fyStart(now);
          final pick = await showModalBottomSheet<String>(
            context: context,
            showDragHandle: true,
            builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
              ListTile(title: Text(t.repThisFy), onTap: () => Navigator.pop(c, 'fy')),
              ListTile(title: Text(t.repLastFy), onTap: () => Navigator.pop(c, 'last')),
              ListTile(title: Text(t.repThisMonth), onTap: () => Navigator.pop(c, 'month')),
              ListTile(title: Text(t.repCustomDates), leading: const Icon(Icons.edit_calendar), onTap: () => Navigator.pop(c, 'custom')),
            ])),
          );
          final n = ref.read(reportRangeProvider.notifier);
          switch (pick) {
            case 'fy':
              n.set(null);
            case 'last':
              n.set(DateTimeRange(start: DateTime(fy.year - 1, 4, 1), end: DateTime(fy.year, 3, 31)));
            case 'month':
              n.set(DateTimeRange(start: DateTime(now.year, now.month, 1), end: DateTime(now.year, now.month + 1, 0)));
            case 'custom':
              if (!context.mounted) return;
              final r = await showDateRangePicker(context: context, firstDate: DateTime(now.year - 5), lastDate: DateTime(now.year + 1, 12, 31),
                  initialDateRange: ref.read(reportRangeProvider) ?? DateTimeRange(start: fy, end: now));
              if (r != null) n.set(r);
          }
        },
      ),
    );
  }
}

Widget _errorView(BuildContext context, WidgetRef ref, Object e, String path) {
  final t = L10n.of(context);
  final err = ApiError.from(e);
  final msg = (err.status == 404 || err.status == 503 || err.isNetwork) ? t.serviceUnavailable : err.message;
  return ListView(children: [ErrorState(message: msg, onRetry: () => ref.invalidate(_reportProvider(path)))]);
}

String _channel(L10n t, String? k) => switch (k) {
      'ONLINE' => t.rcpChannelOnline,
      'CASH' => t.rcpChannelCash,
      'CHEQUE' => t.rcpChannelCheque,
      'BANK_TRANSFER' => t.rcpChannelBank,
      'UPI_DIRECT' => t.repChannelUpi,
      'IN_KIND_VALUE' => t.rcpChannelInKind,
      _ => k ?? '',
    };

String _month(String? k) {
  try {
    return DateFormat('MMM yyyy').format(DateTime.parse('$k-01'));
  } catch (_) {
    return k ?? '';
  }
}

num _n(Object? v) => v is num ? v : num.tryParse('$v') ?? 0;

/// Rows with a proportional bar (largest = full width): month, mode, campaign, donor.
class _Bars extends StatelessWidget {
  const _Bars({required this.rows, required this.label});
  final List rows;
  final String Function(Map) label;

  @override
  Widget build(BuildContext context) {
    final max = rows.fold<num>(0, (m, r) => _n((r as Map)['amount']) > m ? _n(r['amount']) : m);
    return AppCard(
      child: Column(children: [
        for (final r in rows.cast<Map>())
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: Text(label(r), maxLines: 1, overflow: TextOverflow.ellipsis)),
                Text(formatInr(r['amount'], whole: true), style: const TextStyle(fontWeight: FontWeight.w600)),
              ]),
              const SizedBox(height: 4),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(value: max > 0 ? _n(r['amount']) / max : 0, minHeight: 6,
                    backgroundColor: AppColors.primarySoft, color: AppColors.primary),
              ),
            ]),
          ),
      ]),
    );
  }
}

class _NgoReport extends ConsumerWidget {
  const _NgoReport({required this.ngoId});
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final range = ref.watch(reportRangeProvider);
    final q = _query(range);
    final path = '/api/v1/ngos/$ngoId/reports/summary$q';
    final data = ref.watch(_reportProvider(path));
    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(_reportProvider(path)),
      child: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => _errorView(context, ref, e, path),
        data: (r) {
          final d = Map<String, dynamic>.from(r['donations'] as Map);
          final sections = (r['sections'] as List? ?? const []).cast<String>();
          final locked = (r['locked'] as List? ?? const []).cast<String>();
          final period = (r['period'] as Map?)?['label'] as String?;
          final donors = r['donors'] as Map?;
          final pending = r['pending'] as Map?;
          final stamp = DateFormat('yyyy-MM-dd').format(DateTime.now());
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            _PeriodBar(label: range == null ? null : period),
            const SizedBox(height: AppSpacing.sm),
            GridView.count(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              crossAxisCount: 2,
              mainAxisSpacing: AppSpacing.sm,
              crossAxisSpacing: AppSpacing.sm,
              childAspectRatio: 1.4,
              children: [
                StatTile(label: t.repTotalReceived, value: formatInr(d['total'], whole: true), icon: Icons.volunteer_activism),
                StatTile(label: t.navDonations, value: '${d['count']}', icon: Icons.receipt_long, color: AppColors.growth),
                StatTile(label: t.navDonors, value: '${d['donors']}', icon: Icons.people, color: AppColors.professional),
                if (donors != null && donors['newDonors'] != null)
                  StatTile(label: t.repNewDonors, value: '${donors['newDonors']}', icon: Icons.person_add_alt, color: AppColors.enterprise),
              ],
            ),
            if (r['canExport'] == true) ...[
              SectionHeader(t.repDownloadReport),
              Row(children: [
                Expanded(child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                  onPressed: () => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/reports/summary.pdf$q', 'ngo-report-$stamp.pdf', 'application/pdf'),
                  icon: const Icon(Icons.picture_as_pdf_outlined),
                  label: Text(t.accDownloadPdf),
                )),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                  onPressed: () => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/reports/summary.xlsx$q', 'ngo-report-$stamp.xlsx', _xlsx),
                  icon: const Icon(Icons.grid_on_outlined),
                  label: Text(t.accDownloadExcel),
                )),
              ]),
            ],
            if (sections.contains('tax80g')) ...[
              const SizedBox(height: AppSpacing.md),
              AppCard(
                onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => TaxReportPage(ngoId: ngoId))),
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.assignment_outlined, color: AppColors.primary),
                  title: Text(t.repTax80g, style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text(t.repTax80gSub),
                  trailing: const Icon(Icons.chevron_right),
                ),
              ),
            ],
            if (pending != null && (_n(pending['expenses']) > 0 || _n(pending['campaigns']) > 0)) ...[
              SectionHeader(t.repPendingApprovals),
              AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                if (_n(pending['expenses']) > 0) Text(t.repPendingExpenses(_n(pending['expenses']).toInt(), formatInr(pending['expenseAmount']))),
                if (_n(pending['campaigns']) > 0) Text(t.repPendingCampaigns(_n(pending['campaigns']).toInt())),
              ])),
            ],
            SectionHeader(t.repByMonth),
            if ((d['byMonth'] as List).isEmpty)
              EmptyState(icon: Icons.bar_chart, title: t.repNoDonations)
            else
              _Bars(rows: d['byMonth'] as List, label: (r) => _month(r['key'] as String?)),
            if ((d['byChannel'] as List).isNotEmpty) ...[
              SectionHeader(t.repByMode),
              _Bars(rows: d['byChannel'] as List, label: (r) => _channel(t, r['key'] as String?)),
            ],
            if ((d['byCampaign'] as List).isNotEmpty) ...[
              SectionHeader(t.repByCampaign),
              _Bars(rows: d['byCampaign'] as List, label: (r) => (r['label'] as String?) ?? t.repGeneral),
            ],
            if (donors != null && (donors['top'] as List).isNotEmpty) ...[
              SectionHeader(t.repTopDonors),
              _Bars(rows: donors['top'] as List, label: (r) => '${r['label'] ?? ''}'),
            ],
            if (r['campaigns'] != null && (r['campaigns'] as List).isNotEmpty) ...[
              SectionHeader(t.repCampaigns),
              for (final c in (r['campaigns'] as List).cast<Map>()) _CampaignCard(c: c),
            ],
            if (r['staffCash'] != null && (r['staffCash'] as List).isNotEmpty) ...[
              SectionHeader(t.repStaffCash),
              AppCard(child: Column(children: [
                for (final s in (r['staffCash'] as List).cast<Map>())
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.account_circle_outlined),
                    title: Text('${s['name'] ?? ''}'),
                    trailing: Text(
                      _n(s['holds']) > 0 ? t.accHolds(formatInr(s['holds'])) : _n(s['owed']) > 0 ? t.accNgoOwes(formatInr(s['owed'])) : t.repSettled,
                      style: TextStyle(color: _n(s['holds']) > 0 ? AppColors.warning : AppColors.textMuted, fontWeight: FontWeight.w600),
                    ),
                  ),
              ])),
            ],
            if (locked.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.md),
              AppCard(
                color: AppColors.primarySoft,
                onTap: () => context.push('/ngo/subscription/plans'),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    const Icon(Icons.lock_outline, color: AppColors.primary),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(child: Text(t.repLockedTitle, style: const TextStyle(fontWeight: FontWeight.w600))),
                  ]),
                  const SizedBox(height: AppSpacing.sm),
                  if (locked.contains('donors')) Text(t.repLockedGrowth),
                  if (locked.contains('tax80g')) Text(t.repLockedPro),
                  Align(alignment: Alignment.centerRight, child: TextButton(onPressed: () => context.push('/ngo/subscription/plans'), child: Text(t.plans))),
                ]),
              ),
            ],
            const SizedBox(height: AppSpacing.xl),
          ]);
        },
      ),
    );
  }
}

class _CampaignCard extends StatelessWidget {
  const _CampaignCard({required this.c});
  final Map c;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final goal = _n(c['goal']), raised = _n(c['raised']), planned = _n(c['planned']), spent = _n(c['spent']);
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('${c['title'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w600), maxLines: 2, overflow: TextOverflow.ellipsis)),
          Text(t.campaignStatusLabel('${c['status']}'), style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        ]),
        const SizedBox(height: AppSpacing.sm),
        Text(t.repRaisedOf(formatInr(raised, whole: true), formatInr(goal, whole: true)), style: const TextStyle(fontSize: 13)),
        const SizedBox(height: 4),
        LinearProgressIndicator(value: goal > 0 ? (raised / goal).clamp(0, 1).toDouble() : 0, minHeight: 6,
            backgroundColor: AppColors.primarySoft, color: AppColors.primary),
        const SizedBox(height: AppSpacing.sm),
        Text(planned > 0 ? t.repSpentOf(formatInr(spent, whole: true), formatInr(planned, whole: true)) : t.repNoBudget,
            style: const TextStyle(fontSize: 13)),
        if (planned > 0) ...[
          const SizedBox(height: 4),
          LinearProgressIndicator(value: (spent / planned).clamp(0, 1).toDouble(), minHeight: 6,
              backgroundColor: const Color(0xFFFDEBD3), color: spent > planned ? AppColors.error : AppColors.enterprise),
        ],
      ])),
    );
  }
}

/// My report: own collections, own campaigns, own cash and claims (everyone with report access).
class _MyReport extends ConsumerWidget {
  const _MyReport({required this.ngoId});
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final range = ref.watch(reportRangeProvider);
    final path = '/api/v1/ngos/$ngoId/reports/me${_query(range)}';
    final data = ref.watch(_reportProvider(path));
    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(_reportProvider(path)),
      child: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => _errorView(context, ref, e, path),
        data: (r) {
          final col = Map<String, dynamic>.from(r['collections'] as Map);
          final claims = (r['pendingClaims'] as List? ?? const []).cast<Map>();
          final campaigns = (r['campaigns'] as List? ?? const []).cast<Map>();
          final owed = _n(r['owedToYou']);
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            Text(t.repMyReport, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: AppSpacing.sm),
            _PeriodBar(label: range == null ? null : (r['period'] as Map?)?['label'] as String?),
            const SizedBox(height: AppSpacing.sm),
            GridView.count(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              crossAxisCount: 2,
              mainAxisSpacing: AppSpacing.sm,
              crossAxisSpacing: AppSpacing.sm,
              childAspectRatio: 1.4,
              children: [
                StatTile(label: t.repMyCollections, value: formatInr(col['total'], whole: true), icon: Icons.volunteer_activism),
                StatTile(label: owed > 0 ? t.repOwedToYou : t.repCashInHand, value: formatInr(owed > 0 ? owed : r['cashInHand'], whole: true),
                    icon: Icons.account_balance_wallet_outlined, color: AppColors.enterprise),
                StatTile(label: t.repMyClaims, value: '${claims.length}', icon: Icons.pending_actions, color: AppColors.growth),
                StatTile(label: t.repMyCampaigns, value: '${campaigns.length}', icon: Icons.campaign, color: AppColors.professional),
              ],
            ),
            if ((col['byChannel'] as List).isNotEmpty) ...[
              SectionHeader(t.repByMode),
              _Bars(rows: col['byChannel'] as List, label: (x) => _channel(t, x['key'] as String?)),
            ],
            SectionHeader(t.repMyClaims),
            if (claims.isEmpty)
              AppCard(child: Text(t.repNoClaims, style: const TextStyle(color: AppColors.textMuted)))
            else
              AppCard(child: Column(children: [
                for (final c in claims)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.receipt_outlined),
                    title: Text('${c['category'] ?? ''}'),
                    subtitle: Text('${c['campaignTitle'] ?? ''}${c['voucherNo'] != null ? ' · ${c['voucherNo']}' : ''}'),
                    trailing: Text(formatInr(c['amount']), style: const TextStyle(fontWeight: FontWeight.w600)),
                  ),
              ])),
            SectionHeader(t.repMyCampaigns),
            if (campaigns.isEmpty)
              AppCard(child: Text(t.repNoCampaigns, style: const TextStyle(color: AppColors.textMuted)))
            else
              for (final c in campaigns) _CampaignCard(c: c),
            const SizedBox(height: AppSpacing.xl),
          ]);
        },
      ),
    );
  }
}

/// 80G register and Form 10BD data (administrators and Finance, Professional plan and above).
class TaxReportPage extends ConsumerWidget {
  const TaxReportPage({super.key, required this.ngoId});
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final range = ref.watch(reportRangeProvider);
    final q = _query(range);
    final path = '/api/v1/ngos/$ngoId/reports/80g$q';
    final data = ref.watch(_reportProvider(path));
    return Scaffold(
      appBar: AppBar(title: Text(t.repTax80g)),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => _errorView(context, ref, e, path),
        data: (r) {
          final lines = (r['form10bd'] as List? ?? const []).cast<Map>();
          final attention = _n(r['needsAttention']).toInt();
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            _PeriodBar(label: (r['period'] as Map?)?['label'] as String?),
            const SizedBox(height: AppSpacing.sm),
            AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(t.repTaxSummary(_n(r['donations']).toInt(), formatInr(r['total'])), style: const TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 4),
              Text(t.repTaxLines(lines.length)),
              const SizedBox(height: AppSpacing.sm),
              Row(children: [
                Icon(attention > 0 ? Icons.warning_amber_rounded : Icons.check_circle_outline,
                    color: attention > 0 ? AppColors.warning : AppColors.success, size: 20),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: Text(attention > 0 ? t.repTaxAttention(attention) : t.repTaxAllGood)),
              ]),
            ])),
            const SizedBox(height: AppSpacing.md),
            FilledButton.icon(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
              onPressed: () => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/reports/80g.xlsx$q',
                  '80g-form-10bd-${DateFormat('yyyy-MM-dd').format(DateTime.now())}.xlsx', _xlsx),
              icon: const Icon(Icons.grid_on_outlined),
              label: Text(t.repTaxDownload),
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(t.repTaxNote, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
            const SizedBox(height: AppSpacing.md),
            for (final l in lines)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(child: Text('${l['name'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w600))),
                    Text(formatInr(l['amount']), style: const TextStyle(fontWeight: FontWeight.w600)),
                  ]),
                  Text('${(l['idNumber'] as String?)?.isNotEmpty == true ? l['idNumber'] : '-'} · ${l['mode']}',
                      style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                  if ((l['note'] as String?)?.isNotEmpty == true)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text('${l['note']}', style: const TextStyle(color: AppColors.warning, fontSize: 12)),
                    ),
                ])),
              ),
          ]);
        },
      ),
    );
  }
}
