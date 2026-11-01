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
import '../campaigns/campaign_api.dart';
import 'accounts_api.dart';

// ============================================================ budget

/// Budget per category with spend against it. Team members plan it before approval; once live only admins change it.
class BudgetPage extends ConsumerStatefulWidget {
  const BudgetPage({super.key, required this.campaignId});
  final String campaignId;
  @override
  ConsumerState<BudgetPage> createState() => _BudgetPageState();
}

class _Line {
  _Line(this.id, String category, String planned)
      : category = TextEditingController(text: category),
        planned = TextEditingController(text: planned);
  final String? id;
  final TextEditingController category;
  final TextEditingController planned;
}

class _BudgetPageState extends ConsumerState<BudgetPage> {
  List<_Line>? _lines;
  bool _editing = false;
  final _form = GlobalKey<FormState>();

  @override
  void dispose() {
    for (final l in _lines ?? const <_Line>[]) {
      l.category.dispose();
      l.planned.dispose();
    }
    super.dispose();
  }

  static String _plain(Object? v) {
    final n = amountOf(v);
    return n == n.roundToDouble() ? n.toInt().toString() : '$v';
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final key = (ngoId, widget.campaignId);
    final m = ref.watch(currentMembershipProvider);
    final campaign = ref.watch(ngoCampaignProvider(key)).value;
    final status = campaign?['status'];
    final live = status == CampaignStatus.live || status == CampaignStatus.closed;
    final canEdit = status != CampaignStatus.archived && ((m?.isAdmin ?? false) || (!live && (m?.can('CAMPAIGN_MANAGE') ?? false)));
    final budget = ref.watch(budgetProvider(key));
    final sheet = live && (m?.can('EXPENSE_VIEW') ?? false) ? ref.watch(accountSheetProvider(key)).value : null;
    final spentByLine = <String, Map<String, dynamic>>{
      for (final r in (sheet?['budget'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)))
        if (r['lineId'] != null) '${r['lineId']}': r,
    };
    final unbudgeted = (sheet?['budget'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).where((r) => r['budgeted'] != true).toList();

    return Scaffold(
      appBar: AppBar(title: Text(t.accBudget), actions: [
        if (canEdit && !_editing && budget.hasValue)
          IconButton(tooltip: t.accEditBudget, icon: const Icon(Icons.edit_outlined), onPressed: () => _startEdit(budget.value!)),
      ]),
      body: budget.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message),
        data: (b) {
          final lines = (b['lines'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
          final suggestions = (b['suggestedCategories'] as List? ?? const []).map((e) => '$e').toList();
          if (_editing) return _editor(t, ngoId, suggestions);
          final planned = lines.fold<num>(0, (a, l) => a + amountOf(l['planned']));
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            if (campaign != null) Text('${campaign['title']}', style: const TextStyle(color: AppColors.textMuted, fontWeight: FontWeight.w600)),
            const SizedBox(height: AppSpacing.sm),
            if (lines.isEmpty)
              EmptyState(
                icon: Icons.pie_chart_outline,
                title: t.accNoBudget,
                message: t.accNoBudgetHint,
                action: canEdit ? FilledButton.icon(onPressed: () => _startEdit(b), icon: const Icon(Icons.add), label: Text(t.accPlanBudget)) : null,
              )
            else ...[
              AppCard(child: Row(children: [
                Expanded(child: Text(t.accTotalPlanned, style: const TextStyle(fontWeight: FontWeight.w600))),
                Text(formatInr(planned, whole: true), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18)),
              ])),
              const SizedBox(height: AppSpacing.sm),
              for (final l in lines) _BudgetRow(category: '${l['category']}', planned: amountOf(l['planned']), spent: spentByLine['${l['id']}'] == null ? (live ? 0 : null) : amountOf(spentByLine['${l['id']}']!['spent'])),
              for (final u in unbudgeted) _BudgetRow(category: '${u['category']}', planned: null, spent: amountOf(u['spent']), unbudgeted: true),
            ],
            if (!canEdit && lines.isNotEmpty && live) ...[
              const SizedBox(height: AppSpacing.md),
              Text(t.accBudgetAdminOnly, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
            ],
          ]);
        },
      ),
    );
  }

  void _startEdit(Map<String, dynamic> b) {
    final lines = (b['lines'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    setState(() {
      _lines = [for (final l in lines) _Line('${l['id']}', '${l['category']}', _plain(l['planned']))];
      if (_lines!.isEmpty) _lines!.add(_Line(null, '', ''));
      _editing = true;
    });
  }

  Widget _editor(L10n t, String ngoId, List<String> suggestions) {
    final used = _lines!.map((l) => l.category.text.trim().toLowerCase()).toSet();
    return Form(
      key: _form,
      child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
        Text(t.accBudgetEditHint, style: const TextStyle(color: AppColors.textMuted)),
        const SizedBox(height: AppSpacing.md),
        for (final l in _lines!)
          Padding(
            padding: const EdgeInsets.only(bottom: AppSpacing.sm),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Expanded(
                flex: 3,
                child: TextFormField(
                  controller: l.category,
                  decoration: InputDecoration(labelText: t.accCategory),
                  validator: (v) => (v == null || v.trim().length < 2) ? t.tooShort(2) : null,
                  onChanged: (_) => setState(() {}),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                flex: 2,
                child: TextFormField(
                  controller: l.planned,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(labelText: t.accPlanned, prefixText: '₹ '),
                  validator: (v) => RegExp(r'^\d{1,11}(\.\d{1,2})?$').hasMatch(v?.trim() ?? '') ? null : t.invalidAmount,
                ),
              ),
              IconButton(
                tooltip: t.accRemoveLine,
                icon: const Icon(Icons.remove_circle_outline),
                onPressed: () => setState(() => _lines!.remove(l)),
              ),
            ]),
          ),
        Wrap(spacing: 6, children: [
          ActionChip(avatar: const Icon(Icons.add, size: 18), label: Text(t.accAddLine), onPressed: () => setState(() => _lines!.add(_Line(null, '', '')))),
          for (final s in suggestions.where((s) => !used.contains(s.toLowerCase())))
            ActionChip(label: Text(s), onPressed: () => setState(() => _lines!.add(_Line(null, s, '')))),
        ]),
        const SizedBox(height: AppSpacing.lg),
        BusyButton(label: t.save, icon: Icons.check, onPressed: () => _save(ngoId)),
        TextButton(onPressed: () => setState(() => _editing = false), child: Text(t.cancel)),
      ]),
    );
  }

  Future<void> _save(String ngoId) async {
    if (!_form.currentState!.validate()) return;
    try {
      await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/campaigns/${widget.campaignId}/budget', body: {
        'lines': [
          for (final l in _lines!) {if (l.id != null) 'id': l.id, 'category': l.category.text.trim(), 'planned': l.planned.text.trim()},
        ],
      });
      invalidateAccounts(ref, ngoId, widget.campaignId);
      if (mounted) {
        setState(() => _editing = false);
        showSnack(context, L10n.of(context).accBudgetSaved);
      }
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

class _BudgetRow extends StatelessWidget {
  const _BudgetRow({required this.category, required this.planned, required this.spent, this.unbudgeted = false});
  final String category;
  final num? planned;
  final num? spent;
  final bool unbudgeted;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final over = planned != null && spent != null && spent! > planned!;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text(category, style: const TextStyle(fontWeight: FontWeight.w600))),
            if (planned != null) Text(formatInr(planned, whole: true), style: const TextStyle(fontWeight: FontWeight.w700)),
          ]),
          if (unbudgeted)
            Text(t.accNotInBudgetSpent(formatInr(spent, whole: true)), style: const TextStyle(color: Color(0xFFB45309), fontSize: 13)),
          if (!unbudgeted && spent != null) ...[
            const SizedBox(height: 6),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                minHeight: 8,
                value: planned == null || planned == 0 ? (spent! > 0 ? 1 : 0) : (spent! / planned!).clamp(0, 1).toDouble(),
                backgroundColor: AppColors.outline,
                color: over ? AppColors.error : AppColors.primary,
              ),
            ),
            const SizedBox(height: 4),
            Text(over ? t.accOverBudget(formatInr(spent! - planned!, whole: true)) : t.accSpentLeft(formatInr(spent, whole: true), formatInr(planned! - spent!, whole: true)),
                style: TextStyle(color: over ? AppColors.error : AppColors.textMuted, fontSize: 13)),
          ],
        ]),
      ),
    );
  }
}

// ============================================================ account sheet

class AccountSheetPage extends ConsumerWidget {
  const AccountSheetPage({super.key, required this.campaignId});
  final String campaignId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final key = (ngoId, campaignId);
    final data = ref.watch(accountSheetProvider(key));
    final base = '/api/v1/ngos/$ngoId/campaigns/$campaignId';
    String file(String ext) => 'account-sheet-${DateFormat('yyyy-MM-dd').format(DateTime.now())}.$ext';
    return Scaffold(
      appBar: AppBar(title: Text(t.accAccountSheet)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(accountSheetProvider(key)),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(accountSheetProvider(key)))]),
          data: (s) {
            final c = Map<String, dynamic>.from(s['campaign'] as Map);
            final income = Map<String, dynamic>.from(s['income'] as Map);
            final totals = Map<String, dynamic>.from(s['totals'] as Map);
            final budget = (s['budget'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
            final expenses = (s['expenses'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
            final staff = (s['staff'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
            final finalSheet = s['finalSheet'] == true;
            final balance = amountOf(totals['balance']);
            return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              Text('${c['title']}', style: Theme.of(context).textTheme.titleLarge),
              Text('${s['ngoName'] ?? ''}', style: const TextStyle(color: AppColors.textMuted)),
              const SizedBox(height: AppSpacing.sm),
              AppCard(
                color: (finalSheet ? AppColors.success : const Color(0xFFB45309)).withValues(alpha: 0.08),
                child: Row(children: [
                  Icon(finalSheet ? Icons.verified_outlined : Icons.pending_actions, color: finalSheet ? AppColors.success : const Color(0xFFB45309)),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: Text(finalSheet ? t.accFinalNote : t.accProvisionalNote)),
                ]),
              ),
              const SizedBox(height: AppSpacing.md),
              GridView.count(
                crossAxisCount: 2,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                mainAxisSpacing: AppSpacing.sm,
                crossAxisSpacing: AppSpacing.sm,
                childAspectRatio: 1.7,
                children: [
                  StatTile(label: t.accReceived, value: formatInr(income['net'], whole: true), icon: Icons.south_west),
                  StatTile(label: t.accSpent, value: formatInr(totals['spent'], whole: true), icon: Icons.north_east, color: AppColors.growth),
                  StatTile(label: t.accBalance, value: formatInr(balance, whole: true), icon: Icons.account_balance_wallet_outlined,
                      color: balance < 0 ? AppColors.error : AppColors.primary),
                  StatTile(label: t.accStatusSubmitted, value: formatInr(totals['pendingAmount'], whole: true), icon: Icons.hourglass_top,
                      color: const Color(0xFFB45309), onTap: () => context.push('/ngo/campaigns/$campaignId/expenses')),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              Row(children: [
                Expanded(child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                  onPressed: () => downloadAndOffer(context, ref, '$base/accounts.pdf', file('pdf'), 'application/pdf'),
                  icon: const Icon(Icons.picture_as_pdf_outlined),
                  label: Text(t.accDownloadPdf),
                )),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                  onPressed: () => downloadAndOffer(context, ref, '$base/accounts.xlsx', file('xlsx'),
                      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
                  icon: const Icon(Icons.grid_on_outlined),
                  label: Text(t.accDownloadExcel),
                )),
              ]),
              const SizedBox(height: AppSpacing.md),
              SectionHeader(t.accMoneyIn),
              AppCard(child: Column(children: [
                KeyValueRow(t.accOnlineDonations(income['onlineCount'] as int? ?? 0), formatInr(income['online'])),
                if ((income['testCount'] as int? ?? 0) > 0) KeyValueRow(t.accTestDonations(income['testCount'] as int), formatInr(income['test'])),
                if (amountOf(income['refunded']) > 0) KeyValueRow(t.accRefunded, '- ${formatInr(income['refunded'])}'),
                KeyValueRow(t.accReceived, formatInr(income['net'])),
              ])),
              const SizedBox(height: AppSpacing.md),
              SectionHeader(t.accBudgetVsActual),
              AppCard(
                padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
                child: Column(children: [
                  _TableRow([t.accCategory, t.accPlanned, t.accSpent], header: true),
                  for (final b in budget)
                    _TableRow(['${b['category']}${b['budgeted'] == true ? '' : ' *'}', b['budgeted'] == true ? formatInr(b['planned'], whole: true) : '—',
                        formatInr(b['spent'], whole: true)], warn: b['budgeted'] == true && amountOf(b['spent']) > amountOf(b['planned'])),
                  const Divider(),
                  _TableRow([t.accTotal, formatInr(totals['planned'], whole: true), formatInr(totals['spent'], whole: true)], header: true),
                  if (budget.any((b) => b['budgeted'] != true))
                    Align(alignment: Alignment.centerLeft, child: Text('* ${t.accNotInBudget}', style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
                ]),
              ),
              if (staff.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.md),
                SectionHeader(t.accStaffCash),
                AppCard(child: Column(children: [
                  for (final r in staff)
                    KeyValueRow('${r['name']}', staffPosition(t, amountOf(r['balance']))),
                ])),
              ],
              const SizedBox(height: AppSpacing.md),
              SectionHeader(t.accVouchers(expenses.length)),
              if (expenses.isEmpty) Text(t.accNoApprovedYet, style: const TextStyle(color: AppColors.textMuted)),
              for (final e in expenses)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  onTap: () => context.push('/ngo/campaigns/$campaignId/expenses/${e['id']}'),
                  title: Text('${e['description']}', maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text('${e['voucherNo']} · ${formatDate(e['date'])} · ${e['category']}'),
                  trailing: Text(formatInr(e['amount']), style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
              const SizedBox(height: AppSpacing.lg),
            ]);
          },
        ),
      ),
    );
  }
}

String staffPosition(L10n t, num balance) => balance > 0
    ? t.accNgoOwes(formatInr(balance))
    : balance < 0
        ? t.accHolds(formatInr(-balance))
        : t.accSettled;

class _TableRow extends StatelessWidget {
  const _TableRow(this.cells, {this.header = false, this.warn = false});
  final List<String> cells;
  final bool header;
  final bool warn;
  @override
  Widget build(BuildContext context) {
    final style = TextStyle(fontWeight: header ? FontWeight.w700 : FontWeight.normal, color: warn ? AppColors.error : AppColors.text, fontSize: 14);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(children: [
        Expanded(flex: 4, child: Text(cells[0], style: style)),
        Expanded(flex: 3, child: Text(cells[1], style: style, textAlign: TextAlign.right)),
        Expanded(flex: 3, child: Text(cells[2], style: style, textAlign: TextAlign.right)),
      ]),
    );
  }
}
