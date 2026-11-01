import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:io';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../campaigns/campaign_api.dart';
import 'accounts_api.dart';

String _day(Object? iso) {
  final d = parseDay(iso);
  return d == null ? '—' : DateFormat('d MMM yyyy').format(d);
}

// ============================================================ money card on the campaign page

/// Summary of a campaign's money with the ways in: expenses, budget, account sheet. Shown on the team's campaign page.
class CampaignMoneyCard extends ConsumerWidget {
  const CampaignMoneyCard({super.key, required this.ngoId, required this.campaign});
  final String ngoId;
  final Map<String, dynamic> campaign;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final id = '${campaign['id']}';
    final status = '${campaign['status']}';
    final m = ref.watch(currentMembershipProvider);
    final running = status == CampaignStatus.live || status == CampaignStatus.closed || status == CampaignStatus.archived;
    final canRecord = (m?.can('EXPENSE_RECORD') ?? false) && (status == CampaignStatus.live || status == CampaignStatus.closed);
    if (!running) {
      // Before approval only the plan exists: let the team prepare the budget.
      if (!(m?.can('CAMPAIGN_MANAGE') ?? false) || status == CampaignStatus.archived) return const SizedBox.shrink();
      return AppCard(
        onTap: () => context.push('/ngo/campaigns/$id/budget'),
        child: Row(children: [
          const Icon(Icons.account_balance_wallet_outlined, color: AppColors.primary),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(t.accPlanBudget, style: const TextStyle(fontWeight: FontWeight.w700)),
            Text(t.accPlanBudgetHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
          ])),
          const Icon(Icons.chevron_right),
        ]),
      );
    }
    if (!(m?.can('EXPENSE_VIEW') ?? false)) return const SizedBox.shrink();
    final sheet = ref.watch(accountSheetProvider((ngoId, id)));
    return AppCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Icon(Icons.account_balance_wallet_outlined, color: AppColors.primary),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: Text(t.accCampaignMoney, style: Theme.of(context).textTheme.titleMedium)),
          if (sheet.value?['finalSheet'] == true) _Pill(t.accFinal, AppColors.success),
        ]),
        const SizedBox(height: AppSpacing.sm),
        sheet.when(
          loading: () => const Padding(padding: EdgeInsets.all(AppSpacing.md), child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(ApiError.from(e).message, style: const TextStyle(color: AppColors.error)),
          data: (s) {
            final totals = Map<String, dynamic>.from(s['totals'] as Map);
            final income = Map<String, dynamic>.from(s['income'] as Map);
            final balance = amountOf(totals['balance']);
            final pending = (totals['pendingCount'] as num?)?.toInt() ?? 0;
            return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: _Figure(t.accReceived, formatInr(income['net'], whole: true))),
                Expanded(child: _Figure(t.accSpent, formatInr(totals['spent'], whole: true))),
                Expanded(child: _Figure(t.accBalance, formatInr(balance, whole: true), color: balance < 0 ? AppColors.error : AppColors.primary)),
              ]),
              if (amountOf(totals['planned']) > 0) ...[
                const SizedBox(height: AppSpacing.sm),
                ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: LinearProgressIndicator(
                    minHeight: 8,
                    value: (amountOf(totals['spent']) / amountOf(totals['planned'])).clamp(0, 1).toDouble(),
                    backgroundColor: AppColors.outline,
                    color: amountOf(totals['spent']) > amountOf(totals['planned']) ? AppColors.error : AppColors.primary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(t.accOfBudget(formatInr(totals['spent'], whole: true), formatInr(totals['planned'], whole: true)),
                    style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
              ],
              if (pending > 0) ...[
                const SizedBox(height: AppSpacing.sm),
                Row(children: [
                  const Icon(Icons.hourglass_top, size: 18, color: Color(0xFFB45309)),
                  const SizedBox(width: 6),
                  Expanded(child: Text(t.accPendingLine(pending, formatInr(totals['pendingAmount'], whole: true)),
                      style: const TextStyle(color: Color(0xFFB45309), fontWeight: FontWeight.w600))),
                ]),
              ],
            ]);
          },
        ),
        const SizedBox(height: AppSpacing.md),
        if (canRecord)
          FilledButton.icon(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () => context.push('/ngo/campaigns/$id/expenses/new'),
            icon: const Icon(Icons.add_a_photo_outlined),
            label: Text(t.accAddExpense),
          ),
        const SizedBox(height: AppSpacing.sm),
        Row(children: [
          Expanded(child: _LinkButton(Icons.receipt_long_outlined, t.accExpenses, () => context.push('/ngo/campaigns/$id/expenses'))),
          Expanded(child: _LinkButton(Icons.pie_chart_outline, t.accBudget, () => context.push('/ngo/campaigns/$id/budget'))),
          Expanded(child: _LinkButton(Icons.table_chart_outlined, t.accAccountSheet, () => context.push('/ngo/campaigns/$id/accounts'))),
        ]),
      ]),
    );
  }
}

class _Figure extends StatelessWidget {
  const _Figure(this.label, this.value, {this.color = AppColors.text});
  final String label;
  final String value;
  final Color color;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft,
            child: Text(value, style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color))),
      ]);
}

class _LinkButton extends StatelessWidget {
  const _LinkButton(this.icon, this.label, this.onTap);
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => InkWell(
        borderRadius: BorderRadius.circular(AppRadius.button),
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 64),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(icon, color: AppColors.primary),
            const SizedBox(height: 4),
            Text(label, textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          ]),
        ),
      );
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, this.color);
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(AppRadius.chip)),
        child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 12)),
      );
}

// ============================================================ expenses list

class CampaignExpensesPage extends ConsumerStatefulWidget {
  const CampaignExpensesPage({super.key, required this.campaignId});
  final String campaignId;
  @override
  ConsumerState<CampaignExpensesPage> createState() => _CampaignExpensesPageState();
}

class _CampaignExpensesPageState extends ConsumerState<CampaignExpensesPage> {
  String? _filter;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final key = (ngoId, widget.campaignId);
    final m = ref.watch(currentMembershipProvider);
    final campaign = ref.watch(ngoCampaignProvider(key)).value;
    final status = campaign?['status'];
    final canRecord = (m?.can('EXPENSE_RECORD') ?? false) && (status == CampaignStatus.live || status == CampaignStatus.closed);
    final data = ref.watch(campaignExpensesProvider(key));
    final filters = <String?, String>{
      null: t.accFilterAll,
      ExpenseStatus.submitted: t.accStatusSubmitted,
      ExpenseStatus.approved: t.accStatusApproved,
      ExpenseStatus.rejected: t.accStatusRejected,
      ExpenseStatus.voided: t.accStatusVoid,
    };
    return Scaffold(
      appBar: AppBar(title: Text(t.accExpenses)),
      floatingActionButton: canRecord
          ? FloatingActionButton.extended(
              onPressed: () => context.push('/ngo/campaigns/${widget.campaignId}/expenses/new'),
              icon: const Icon(Icons.add_a_photo_outlined),
              label: Text(t.accAddExpense),
            )
          : null,
      body: RefreshIndicator(
        onRefresh: () async => invalidateAccounts(ref, ngoId, widget.campaignId),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(campaignExpensesProvider(key)))]),
          data: (all) {
            final list = _filter == null ? all : all.where((e) => e['status'] == _filter).toList();
            num sum(String s) => all.where((e) => e['status'] == s).fold<num>(0, (a, e) => a + amountOf(e['amount']));
            return ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 96), children: [
              if (campaign != null)
                Text('${campaign['title']}', style: const TextStyle(color: AppColors.textMuted, fontWeight: FontWeight.w600)),
              const SizedBox(height: AppSpacing.sm),
              Row(children: [
                Expanded(child: _Figure(t.accApprovedTotal, formatInr(sum(ExpenseStatus.approved), whole: true), color: AppColors.primary)),
                Expanded(child: _Figure(t.accStatusSubmitted, formatInr(sum(ExpenseStatus.submitted), whole: true), color: const Color(0xFFB45309))),
              ]),
              const SizedBox(height: AppSpacing.md),
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(children: [
                  for (final f in filters.entries)
                    Padding(
                      padding: const EdgeInsets.only(right: AppSpacing.sm),
                      child: ChoiceChip(
                        label: Text(f.key == null ? f.value : '${f.value} (${all.where((e) => e['status'] == f.key).length})'),
                        selected: _filter == f.key,
                        onSelected: (_) => setState(() => _filter = f.key),
                      ),
                    ),
                ]),
              ),
              const SizedBox(height: AppSpacing.sm),
              if (list.isEmpty)
                EmptyState(icon: Icons.receipt_long_outlined, title: t.accNoExpenses, message: t.accNoExpensesHint)
              else
                for (final e in list) ExpenseTile(ngoId: ngoId, expense: e),
            ]);
          },
        ),
      ),
    );
  }
}

class ExpenseTile extends ConsumerWidget {
  const ExpenseTile({super.key, required this.ngoId, required this.expense, this.showCampaign = false});
  final String ngoId;
  final Map<String, dynamic> expense;
  final bool showCampaign;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final e = expense;
    final status = '${e['status']}';
    final bills = (e['attachmentCount'] as num?)?.toInt() ?? 0;
    final sub = [
      if (showCampaign && e['campaignTitle'] != null) '${e['campaignTitle']}',
      '${e['category']}',
      _day(e['date']),
      if (e['vendorName'] != null) '${e['vendorName']}',
    ].join(' · ');
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        onTap: () => context.push('/ngo/campaigns/${e['campaignId']}/expenses/${e['id']}'),
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(color: AppColors.primarySoft, borderRadius: BorderRadius.circular(10)),
            child: Icon(bills > 0 ? Icons.receipt_long : Icons.receipt_long_outlined, color: AppColors.primary),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${e['description']}', style: const TextStyle(fontWeight: FontWeight.w600), maxLines: 2, overflow: TextOverflow.ellipsis),
            const SizedBox(height: 2),
            Text(sub, style: const TextStyle(color: AppColors.textMuted, fontSize: 13), maxLines: 2, overflow: TextOverflow.ellipsis),
            const SizedBox(height: 6),
            Wrap(spacing: 6, runSpacing: 4, children: [
              _Pill(expenseStatusLabel(t, status), expenseStatusColor(status)),
              if (e['voucherNo'] != null) _Pill('${e['voucherNo']}', AppColors.textMuted),
              if (bills == 0) _Pill(t.accNoBill, AppColors.textMuted),
            ]),
          ])),
          const SizedBox(width: AppSpacing.sm),
          Text(formatInr(e['amount']), style: TextStyle(
              fontWeight: FontWeight.w700, fontSize: 15,
              decoration: status == ExpenseStatus.voided ? TextDecoration.lineThrough : null)),
        ]),
      ),
    );
  }
}

// ============================================================ add / edit

class ExpenseFormPage extends ConsumerStatefulWidget {
  const ExpenseFormPage({super.key, required this.campaignId, this.expenseId});
  final String campaignId;
  final String? expenseId;
  @override
  ConsumerState<ExpenseFormPage> createState() => _ExpenseFormPageState();
}

class _ExpenseFormPageState extends ConsumerState<ExpenseFormPage> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _description = TextEditingController();
  final _billNo = TextEditingController();
  final _payee = TextEditingController();
  final _noBillReason = TextEditingController();
  final _category = TextEditingController();
  DateTime _date = today();
  String? _lineId;
  String? _vendorId;
  String? _vendorName;
  String _mode = 'UPI';
  String _paidBy = 'NGO';
  String? _payer; // user id when paidBy == STAFF
  bool _noBill = false;
  bool _otherCategory = false; // budget exists but the bill is outside it
  final List<PickedBill> _bills = [];
  Map<String, dynamic>? _existing;
  bool _loaded = false;

  bool get _editing => widget.expenseId != null;

  @override
  void dispose() {
    for (final c in [_amount, _description, _billNo, _payee, _noBillReason, _category]) {
      c.dispose();
    }
    super.dispose();
  }

  void _fill(Map<String, dynamic> e) {
    _existing = e;
    _amount.text = amountOf(e['amount']) == amountOf(e['amount']).roundToDouble()
        ? amountOf(e['amount']).toInt().toString() : '${e['amount']}';
    _description.text = '${e['description'] ?? ''}';
    _billNo.text = '${e['billNumber'] ?? ''}';
    _date = parseDay(e['date']) ?? today();
    _lineId = e['budgetLineId'] as String?;
    _otherCategory = _lineId == null;
    _category.text = _lineId == null ? '${e['category'] ?? ''}' : '';
    _vendorId = e['vendorId'] as String?;
    _vendorName = e['vendorName'] as String?;
    if (_vendorId == null) _payee.text = _vendorName ?? '';
    _mode = '${e['paymentMode']}';
    _paidBy = '${e['paidBy']}';
    _payer = e['paidByUser'] as String?;
    _noBill = e['noBillReason'] != null;
    _noBillReason.text = '${e['noBillReason'] ?? ''}';
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final key = (ngoId, widget.campaignId);
    if (_editing && !_loaded) {
      final e = ref.watch(expenseProvider((ngoId, widget.campaignId, widget.expenseId!)));
      return e.when(
        loading: () => const Scaffold(body: LoadingView()),
        error: (err, _) => Scaffold(appBar: AppBar(), body: ErrorState(message: ApiError.from(err).message)),
        data: (x) {
          WidgetsBinding.instance.addPostFrameCallback((_) => setState(() { _fill(x); _loaded = true; }));
          return const Scaffold(body: LoadingView());
        },
      );
    }
    final m = ref.watch(currentMembershipProvider);
    final me = ref.watch(meProvider).value;
    final admin = m?.isAdmin ?? false;
    final budget = ref.watch(budgetProvider(key)).value;
    final lines = (budget?['lines'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final suggestions = (budget?['suggestedCategories'] as List? ?? const []).map((e) => '$e').toList();
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final existingBills = (_existing?['attachments'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final billCount = _bills.length + existingBills.length;

    return Scaffold(
      appBar: AppBar(title: Text(_editing ? t.accEditExpense : t.accAddExpense)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          // ---- bill photos first: the evidence is the point
          Text(t.accBillPhotos, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text(t.accBillPhotosHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
          const SizedBox(height: AppSpacing.sm),
          SizedBox(
            height: 112,
            child: ListView(scrollDirection: Axis.horizontal, children: [
              for (final b in existingBills)
                _BillThumb(
                  child: b['contentType'] == 'application/pdf'
                      ? const _PdfIcon()
                      : _NetBill(ngoId: ngoId, campaignId: widget.campaignId, expenseId: widget.expenseId!, attachmentId: '${b['id']}'),
                  onRemove: () => _removeExisting(ngoId, '${b['id']}'),
                ),
              for (final b in _bills)
                _BillThumb(
                  child: b.isPdf ? const _PdfIcon() : Image.memory(b.bytes, fit: BoxFit.cover),
                  onRemove: () => setState(() => _bills.remove(b)),
                ),
              if (billCount < maxBills) ...[
                _AddBillButton(icon: Icons.photo_camera_outlined, label: t.accTakePhoto, onTap: () => _pick(ngoId, ImageSource.camera)),
                _AddBillButton(icon: Icons.photo_library_outlined, label: t.accFromGallery, onTap: () => _pick(ngoId, ImageSource.gallery)),
                _AddBillButton(icon: Icons.picture_as_pdf_outlined, label: t.accPdf, onTap: () => _pickPdf(ngoId)),
              ],
            ]),
          ),
          if (billCount == 0) ...[
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _noBill,
              onChanged: (v) => setState(() => _noBill = v),
              title: Text(t.accNoBillSwitch),
            ),
            if (_noBill)
              TextFormField(
                controller: _noBillReason,
                maxLength: 200,
                decoration: InputDecoration(labelText: t.accNoBillReason, hintText: t.accNoBillReasonHint),
                validator: V(t).text(min: 5, max: 200),
              ),
          ],
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: t.accAmount, prefixText: '₹ '),
            validator: V(t).amount(min: 1, max: 10000000),
          ),
          const SizedBox(height: AppSpacing.md),
          InkWell(
            onTap: () async {
              final d = await showDatePicker(context: context, initialDate: _date, firstDate: today().subtract(const Duration(days: 400)), lastDate: today());
              if (d != null) setState(() => _date = d);
            },
            child: InputDecorator(
              decoration: InputDecoration(labelText: t.accBillDate, suffixIcon: const Icon(Icons.calendar_today_outlined)),
              child: Text(DateFormat('d MMM yyyy').format(_date)),
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          if (lines.isNotEmpty)
            DropdownButtonFormField<String>(
              initialValue: _lineId ?? (_otherCategory ? '__other' : null),
              isExpanded: true,
              hint: Text(t.accChooseCategory),
              decoration: InputDecoration(labelText: t.accCategory),
              items: [
                for (final l in lines) DropdownMenuItem(value: '${l['id']}', child: Text('${l['category']}')),
                DropdownMenuItem(value: '__other', child: Text(t.accNotInBudget)),
              ],
              validator: (v) => v == null ? t.required : null,
              onChanged: (v) => setState(() {
                _otherCategory = v == '__other';
                _lineId = _otherCategory ? null : v;
              }),
            ),
          if (lines.isEmpty || _otherCategory) ...[
            if (lines.isNotEmpty) const SizedBox(height: AppSpacing.sm),
            TextFormField(
              controller: _category,
              decoration: InputDecoration(labelText: lines.isEmpty ? t.accCategory : t.accOtherCategory, hintText: t.accCategoryHint),
              validator: V(t).text(min: 2, max: 60, optional: true),
            ),
            if (suggestions.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.xs),
                child: Wrap(spacing: 6, runSpacing: 0, children: [
                  for (final s in suggestions)
                    ActionChip(label: Text(s), onPressed: () => setState(() => _category.text = s)),
                ]),
              ),
          ],
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _description,
            maxLength: 300,
            decoration: InputDecoration(labelText: t.accWhatFor, hintText: t.accWhatForHint),
            validator: V(t).text(min: 3, max: 300),
          ),
          const SizedBox(height: AppSpacing.sm),
          // ---- paid to
          _VendorField(
            ngoId: ngoId,
            vendorName: _vendorId == null ? null : _vendorName,
            onVendor: (id, name) => setState(() { _vendorId = id; _vendorName = name; }),
          ),
          if (_vendorId == null) ...[
            const SizedBox(height: AppSpacing.sm),
            TextFormField(
              controller: _payee,
              decoration: InputDecoration(labelText: t.accPaidToOneOff, hintText: t.accPaidToOneOffHint),
              validator: V(t).text(max: 120, optional: true),
            ),
          ],
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _billNo,
            decoration: InputDecoration(labelText: t.accBillNumber),
            validator: V(t).text(max: 40, optional: true),
          ),
          const SizedBox(height: AppSpacing.md),
          Text(t.accHowPaid, style: const TextStyle(fontWeight: FontWeight.w600)),
          Wrap(spacing: 6, children: [
            for (final mode in paymentModes)
              ChoiceChip(label: Text(paymentModeLabel(t, mode)), selected: _mode == mode, onSelected: (_) => setState(() => _mode = mode)),
          ]),
          const SizedBox(height: AppSpacing.md),
          Text(t.accWhoPaid, style: const TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          SegmentedButton<String>(
            segments: [
              ButtonSegment(value: 'NGO', label: Text(t.accPaidByNgo), icon: const Icon(Icons.account_balance_outlined)),
              ButtonSegment(value: 'STAFF', label: Text(admin ? t.accPaidByStaff : t.accPaidByMe), icon: const Icon(Icons.person_outline)),
            ],
            selected: {_paidBy},
            onSelectionChanged: (s) => setState(() {
              _paidBy = s.first;
              if (_paidBy == 'STAFF') _payer ??= me?.userId;
            }),
          ),
          if (_paidBy == 'STAFF') ...[
            const SizedBox(height: AppSpacing.sm),
            if (admin)
              DropdownButtonFormField<String>(
                initialValue: _payer ?? me?.userId,
                isExpanded: true,
                decoration: InputDecoration(labelText: t.accWhichStaff),
                items: [
                  for (final e in names.entries) DropdownMenuItem(value: e.key, child: Text(e.key == me?.userId ? '${e.value} (${t.you})' : e.value)),
                ],
                onChanged: (v) => setState(() => _payer = v),
              ),
            Text(t.accPaidByStaffHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
          ],
          const SizedBox(height: AppSpacing.lg),
          BusyButton(label: _editing ? t.accSaveAndResend : t.accSendForApproval, icon: Icons.send, onPressed: () => _save(ngoId)),
          const SizedBox(height: AppSpacing.sm),
          Text(t.accApprovalNote, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
        ]),
      ),
    );
  }

  Future<void> _pick(String ngoId, ImageSource source) async {
    final t = L10n.of(context);
    try {
      final f = await ImagePicker().pickImage(source: source, maxWidth: 1600, maxHeight: 1600, imageQuality: 80);
      if (f == null) return;
      final bytes = await f.readAsBytes();
      await _addBill(ngoId, PickedBill(bytes, f.name.isEmpty ? 'bill.jpg' : f.name, false));
    } catch (e) {
      if (mounted) showSnack(context, t.accCouldNotReadFile, error: true);
    }
  }

  Future<void> _pickPdf(String ngoId) async {
    final f = await FilePicker.pickFile(type: FileType.custom, allowedExtensions: ['pdf']);
    if (f == null) return;
    final bytes = await f.readAsBytes();
    if (!mounted) return;
    if (bytes.length > 10 * 1024 * 1024) {
      showSnack(context, L10n.of(context).accFileTooLarge, error: true);
      return;
    }
    await _addBill(ngoId, PickedBill(Uint8List.fromList(bytes), f.name, true));
  }

  /// New expense: keep locally until saved. Existing expense: upload straight away.
  Future<void> _addBill(String ngoId, PickedBill b) async {
    if (!_editing) {
      setState(() { _bills.add(b); _noBill = false; });
      return;
    }
    try {
      final r = await ref.read(apiProvider).upload('/api/v1/ngos/$ngoId/campaigns/${widget.campaignId}/expenses/${widget.expenseId}/attachments',
          bytes: b.bytes, filename: b.name);
      setState(() => _existing = {...?_existing, 'attachments': r['attachments'], 'version': r['version']});
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  Future<void> _removeExisting(String ngoId, String attachmentId) async {
    final t = L10n.of(context);
    if (!await confirm(context, title: t.accRemoveBill, message: t.accRemoveBillConfirm, confirmLabel: t.accRemoveBill, cancelLabel: t.cancel, destructive: true)) return;
    try {
      final api = ref.read(apiProvider);
      await api.delete('/api/v1/ngos/$ngoId/campaigns/${widget.campaignId}/expenses/${widget.expenseId}/attachments/$attachmentId');
      final e = await api.getJson('/api/v1/ngos/$ngoId/campaigns/${widget.campaignId}/expenses/${widget.expenseId}');
      setState(() => _existing = {...?_existing, 'attachments': e['attachments'], 'version': e['version']});
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  Future<void> _save(String ngoId) async {
    final t = L10n.of(context);
    if (!_form.currentState!.validate()) return;
    final billCount = _bills.length + ((_existing?['attachments'] as List?)?.length ?? 0);
    if (billCount == 0 && !_noBill) {
      showSnack(context, t.accBillRequired, error: true);
      return;
    }
    final body = <String, dynamic>{
      'budgetLineId': _lineId,
      'category': _lineId == null ? (_category.text.trim().isEmpty ? null : _category.text.trim()) : null,
      'description': _description.text.trim(),
      'amount': _amount.text.trim(),
      'date': isoDay(_date),
      'vendorId': _vendorId,
      'vendorName': _vendorId == null && _payee.text.trim().isNotEmpty ? _payee.text.trim() : null,
      'billNumber': _billNo.text.trim().isEmpty ? null : _billNo.text.trim(),
      'noBillReason': billCount == 0 && _noBill ? _noBillReason.text.trim() : null,
      'paymentMode': _mode,
      'paidBy': _paidBy,
      'paidByUser': _paidBy == 'STAFF' ? _payer : null,
      if (_editing) 'version': _existing?['version'],
    }..removeWhere((_, v) => v == null);
    try {
      final api = ref.read(apiProvider);
      final Map<String, dynamic> saved;
      if (_editing) {
        saved = await api.put('/api/v1/ngos/$ngoId/campaigns/${widget.campaignId}/expenses/${widget.expenseId}', body: body);
      } else {
        saved = await createExpense(api, (ngoId, widget.campaignId), body, _bills);
      }
      invalidateAccounts(ref, ngoId, widget.campaignId, '${saved['id']}');
      if (!mounted) return;
      showSnack(context, t.accSentForApproval);
      if (_editing) {
        context.pop();
      } else {
        context.pushReplacement('/ngo/campaigns/${widget.campaignId}/expenses/${saved['id']}');
      }
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

class _BillThumb extends StatelessWidget {
  const _BillThumb({required this.child, required this.onRemove});
  final Widget child;
  final VoidCallback onRemove;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(right: AppSpacing.sm),
        child: Stack(children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: SizedBox(width: 84, height: 112, child: ColoredBox(color: AppColors.primarySoft, child: child)),
          ),
          Positioned(
            right: 0,
            top: 0,
            child: Material(
              color: Colors.black54,
              shape: const CircleBorder(),
              child: IconButton(
                visualDensity: VisualDensity.compact,
                tooltip: L10n.of(context).accRemoveBill,
                icon: const Icon(Icons.close, color: Colors.white, size: 18),
                onPressed: onRemove,
              ),
            ),
          ),
        ]),
      );
}

class _AddBillButton extends StatelessWidget {
  const _AddBillButton({required this.icon, required this.label, required this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(right: AppSpacing.sm),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(12),
          child: Container(
            width: 84,
            height: 112,
            decoration: BoxDecoration(
              border: Border.all(color: AppColors.primary.withValues(alpha: 0.5)),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
              Icon(icon, color: AppColors.primary),
              const SizedBox(height: 6),
              Text(label, textAlign: TextAlign.center, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
            ]),
          ),
        ),
      );
}

class _PdfIcon extends StatelessWidget {
  const _PdfIcon();
  @override
  Widget build(BuildContext context) => const Center(child: Icon(Icons.picture_as_pdf, color: AppColors.error, size: 40));
}

class _NetBill extends ConsumerWidget {
  const _NetBill({required this.ngoId, required this.campaignId, required this.expenseId, required this.attachmentId});
  final String ngoId, campaignId, expenseId, attachmentId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final b = ref.watch(billBytesProvider((ngoId, campaignId, expenseId, attachmentId)));
    return b.when(
      loading: () => const Center(child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
      error: (_, __) => const Center(child: Icon(Icons.broken_image_outlined)),
      data: (bytes) => Image.memory(bytes, fit: BoxFit.cover, width: double.infinity, height: double.infinity),
    );
  }
}

/// Picks a vendor from the NGO's list (search) or adds a new one on the spot.
class _VendorField extends ConsumerWidget {
  const _VendorField({required this.ngoId, required this.vendorName, required this.onVendor});
  final String ngoId;
  final String? vendorName;
  final void Function(String? id, String? name) onVendor;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    return InputDecorator(
      decoration: InputDecoration(
        labelText: t.accPaidTo,
        suffixIcon: vendorName == null
            ? const Icon(Icons.storefront_outlined)
            : IconButton(tooltip: t.accClear, icon: const Icon(Icons.close), onPressed: () => onVendor(null, null)),
      ),
      child: InkWell(
        onTap: () async {
          final v = await showModalBottomSheet<Map<String, dynamic>>(
            context: context,
            isScrollControlled: true,
            showDragHandle: true,
            builder: (_) => VendorPickerSheet(ngoId: ngoId),
          );
          if (v != null) onVendor('${v['id']}', '${v['name']}');
        },
        child: Text(vendorName ?? t.accChooseVendor,
            style: TextStyle(color: vendorName == null ? AppColors.textMuted : AppColors.text, fontSize: 16)),
      ),
    );
  }
}

class VendorPickerSheet extends ConsumerStatefulWidget {
  const VendorPickerSheet({super.key, required this.ngoId});
  final String ngoId;
  @override
  ConsumerState<VendorPickerSheet> createState() => _VendorPickerSheetState();
}

class _VendorPickerSheetState extends ConsumerState<VendorPickerSheet> {
  String _q = '';
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final data = ref.watch(vendorsProvider(widget.ngoId));
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.75,
        child: Column(children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
            child: TextField(
              autofocus: false,
              decoration: InputDecoration(prefixIcon: const Icon(Icons.search), hintText: t.accSearchVendors),
              onChanged: (v) => setState(() => _q = v.trim().toLowerCase()),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.add_business_outlined, color: AppColors.primary),
            title: Text(t.accNewVendor, style: const TextStyle(color: AppColors.primary, fontWeight: FontWeight.w600)),
            onTap: () async {
              final v = await showModalBottomSheet<Map<String, dynamic>>(
                  context: context, isScrollControlled: true, showDragHandle: true,
                  builder: (_) => VendorFormSheet(ngoId: widget.ngoId, initialName: _q.isEmpty ? null : _q));
              if (v != null && context.mounted) Navigator.pop(context, v);
            },
          ),
          const Divider(height: 1),
          Expanded(
            child: data.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorState(message: ApiError.from(e).message),
              data: (all) {
                final list = all.where((v) => v['active'] == true && (_q.isEmpty || '${v['name']} ${v['category'] ?? ''} ${v['phone'] ?? ''}'.toLowerCase().contains(_q))).toList();
                if (list.isEmpty) return Center(child: Text(t.accNoVendorsFound, style: const TextStyle(color: AppColors.textMuted)));
                return ListView(children: [
                  for (final v in list)
                    ListTile(
                      title: Text('${v['name']}'),
                      subtitle: Text([v['category'], v['phone']].whereType<String>().join(' · ')),
                      onTap: () => Navigator.pop(context, v),
                    ),
                ]);
              },
            ),
          ),
        ]),
      ),
    );
  }
}

/// Add or edit a vendor. Returns the saved vendor.
class VendorFormSheet extends ConsumerStatefulWidget {
  const VendorFormSheet({super.key, required this.ngoId, this.vendor, this.initialName});
  final String ngoId;
  final Map<String, dynamic>? vendor;
  final String? initialName;
  @override
  ConsumerState<VendorFormSheet> createState() => _VendorFormSheetState();
}

class _VendorFormSheetState extends ConsumerState<VendorFormSheet> {
  final _form = GlobalKey<FormState>();
  late final _name = TextEditingController(text: widget.vendor?['name'] ?? widget.initialName ?? '');
  late final _phone = TextEditingController(text: widget.vendor?['phone'] ?? '');
  late final _email = TextEditingController(text: widget.vendor?['email'] ?? '');
  late final _gstin = TextEditingController(text: widget.vendor?['gstin'] ?? '');
  late final _pan = TextEditingController(text: widget.vendor?['pan'] ?? '');
  late final _address = TextEditingController(text: widget.vendor?['address'] ?? '');
  late final _category = TextEditingController(text: widget.vendor?['category'] ?? '');
  late bool _active = widget.vendor?['active'] ?? true;
  static final _gstinRe = RegExp(r'^[0-9]{2}[A-Za-z]{5}[0-9]{4}[A-Za-z][1-9A-Za-z][Zz][0-9A-Za-z]$');

  @override
  void dispose() {
    for (final c in [_name, _phone, _email, _gstin, _pan, _address, _category]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final v = V(t);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Form(
        key: _form,
        child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(AppSpacing.md), children: [
          Text(widget.vendor == null ? t.accNewVendor : t.accEditVendor, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: AppSpacing.md),
          TextFormField(controller: _name, decoration: InputDecoration(labelText: t.accVendorName), validator: v.text(min: 2, max: 120)),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(controller: _phone, keyboardType: TextInputType.phone, decoration: InputDecoration(labelText: t.accVendorPhone),
              validator: v.phoneField(optional: true)),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(controller: _category, decoration: InputDecoration(labelText: t.accVendorCategory, hintText: t.accVendorCategoryHint),
              validator: v.text(max: 60, optional: true)),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(controller: _gstin, textCapitalization: TextCapitalization.characters, decoration: InputDecoration(labelText: t.accVendorGstin),
              validator: v.text(optional: true, max: 15, pattern: _gstinRe, patternMessage: t.accInvalidGstin)),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(controller: _pan, textCapitalization: TextCapitalization.characters, decoration: InputDecoration(labelText: t.accVendorPan),
              validator: v.panField),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(controller: _email, keyboardType: TextInputType.emailAddress, decoration: InputDecoration(labelText: t.accVendorEmail),
              validator: v.emailField(optional: true)),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(controller: _address, maxLines: 2, decoration: InputDecoration(labelText: t.accVendorAddress),
              validator: v.text(max: 300, optional: true)),
          if (widget.vendor != null)
            SwitchListTile(contentPadding: EdgeInsets.zero, value: _active, onChanged: (x) => setState(() => _active = x), title: Text(t.accVendorActive)),
          const SizedBox(height: AppSpacing.md),
          BusyButton(label: t.save, icon: Icons.check, onPressed: _save),
        ]),
      ),
    );
  }

  Future<void> _save() async {
    if (!_form.currentState!.validate()) return;
    String? opt(TextEditingController c) => c.text.trim().isEmpty ? null : c.text.trim();
    final body = {
      'name': _name.text.trim(), 'phone': opt(_phone), 'email': opt(_email), 'gstin': opt(_gstin), 'pan': opt(_pan),
      'address': opt(_address), 'category': opt(_category),
      if (widget.vendor != null) ...{'active': _active, 'version': widget.vendor!['version']},
    };
    try {
      final api = ref.read(apiProvider);
      final r = widget.vendor == null
          ? await api.post('/api/v1/ngos/${widget.ngoId}/vendors', body: body)
          : await api.put('/api/v1/ngos/${widget.ngoId}/vendors/${widget.vendor!['id']}', body: body);
      ref.invalidate(vendorsProvider(widget.ngoId));
      if (mounted) Navigator.pop(context, r);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

// ============================================================ detail

class ExpenseDetailPage extends ConsumerWidget {
  const ExpenseDetailPage({super.key, required this.campaignId, required this.expenseId});
  final String campaignId;
  final String expenseId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(expenseProvider((ngoId, campaignId, expenseId)));
    return Scaffold(
      appBar: AppBar(title: Text(t.accExpense)),
      body: RefreshIndicator(
        onRefresh: () async => invalidateAccounts(ref, ngoId, campaignId, expenseId),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message)]),
          data: (e) => _ExpenseBody(ngoId: ngoId, campaignId: campaignId, e: e),
        ),
      ),
    );
  }
}

class _ExpenseBody extends ConsumerWidget {
  const _ExpenseBody({required this.ngoId, required this.campaignId, required this.e});
  final String ngoId;
  final String campaignId;
  final Map<String, dynamic> e;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final id = '${e['id']}';
    final status = '${e['status']}';
    final m = ref.watch(currentMembershipProvider);
    final me = ref.watch(meProvider).value;
    final admin = m?.isAdmin ?? false;
    final mine = e['submittedBy'] == me?.userId;
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final campaign = ref.watch(ngoCampaignProvider((ngoId, campaignId))).value;
    String who(Object? userId) => userId == null ? '—' : (userId == me?.userId ? t.you : names['$userId'] ?? t.aTeamMember);
    final bills = (e['attachments'] as List? ?? const []).map((x) => Map<String, dynamic>.from(x as Map)).toList();
    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/campaigns/$campaignId/expenses/$id';
    final archived = campaign?['status'] == CampaignStatus.archived;

    Future<void> run(Future<void> Function() action, String done, {bool pop = false}) async {
      try {
        await action();
        invalidateAccounts(ref, ngoId, campaignId, id);
        if (context.mounted) {
          showSnack(context, done);
          if (pop) context.pop();
        }
      } catch (err) {
        if (context.mounted) showSnack(context, ApiError.from(err).message, error: true);
      }
    }

    final actions = <Widget>[];
    if (status == ExpenseStatus.submitted && admin && !archived) {
      actions.add(BusyButton(label: t.accApprove, icon: Icons.check_circle, onPressed: () async {
        if (!await confirm(context, title: t.accApprove, message: t.accApproveConfirm(formatInr(e['amount'])), confirmLabel: t.accApprove, cancelLabel: t.cancel)) return;
        await run(() => api.post('$base/approve', body: {'version': e['version']}), t.accApproved);
      }));
      actions.add(BusyButton(label: t.accSendBack, icon: Icons.undo, outlined: true, onPressed: () async {
        final reason = await askReason(context, t.accSendBack, t.accSendBackHint);
        if (reason == null) return;
        await run(() => api.post('$base/reject', body: {'reason': reason}), t.accSentBack);
      }));
    }
    if ((status == ExpenseStatus.submitted || status == ExpenseStatus.rejected) && (mine || admin) && !archived) {
      actions.add(OutlinedButton.icon(
        style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
        onPressed: () => context.push('/ngo/campaigns/$campaignId/expenses/$id/edit'),
        icon: const Icon(Icons.edit_outlined),
        label: Text(status == ExpenseStatus.rejected ? t.accCorrectAndResend : t.accEditExpense),
      ));
    }
    if ((status == ExpenseStatus.submitted || status == ExpenseStatus.rejected) && mine) {
      actions.add(TextButton.icon(
        style: TextButton.styleFrom(foregroundColor: AppColors.error, minimumSize: const Size.fromHeight(kMinTouchTarget)),
        icon: const Icon(Icons.delete_outline),
        label: Text(t.accDeleteExpense),
        onPressed: () async {
          if (!await confirm(context, title: t.accDeleteExpense, message: t.accDeleteConfirm, confirmLabel: t.accDeleteExpense, cancelLabel: t.cancel, destructive: true)) return;
          await run(() => api.delete(base), t.accDeleted, pop: true);
        },
      ));
    }
    if (status == ExpenseStatus.approved && admin && !archived) {
      actions.add(TextButton.icon(
        style: TextButton.styleFrom(foregroundColor: AppColors.error, minimumSize: const Size.fromHeight(kMinTouchTarget)),
        icon: const Icon(Icons.block),
        label: Text(t.accVoid),
        onPressed: () async {
          final reason = await askReason(context, t.accVoid, t.accVoidHint);
          if (reason == null) return;
          await run(() => api.post('$base/void', body: {'reason': reason}), t.accVoided);
        },
      ));
    }

    return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(formatInr(e['amount']), style: Theme.of(context).textTheme.headlineMedium?.copyWith(
              fontWeight: FontWeight.w700, decoration: status == ExpenseStatus.voided ? TextDecoration.lineThrough : null)),
          const SizedBox(height: 4),
          Text('${e['description']}', style: const TextStyle(fontSize: 16)),
          if (campaign != null) Text('${campaign['title']}', style: const TextStyle(color: AppColors.textMuted)),
        ])),
        _Pill(expenseStatusLabel(t, status), expenseStatusColor(status)),
      ]),
      const SizedBox(height: AppSpacing.md),
      if (status == ExpenseStatus.submitted)
        _Note(icon: Icons.hourglass_top, color: const Color(0xFFB45309),
            text: admin && !mine ? t.accPendingAdmin : (admin && mine ? t.accPendingOwnAdmin : t.accPendingTeam)),
      if (status == ExpenseStatus.rejected)
        _Note(icon: Icons.feedback_outlined, color: AppColors.error, title: t.accSentBackBy(who(e['decidedBy'])), text: '${e['rejectionReason']}'),
      if (status == ExpenseStatus.voided)
        _Note(icon: Icons.block, color: AppColors.textMuted, title: t.accVoidedBy(who(e['voidedBy'])), text: '${e['voidReason']}'),
      // Bills
      SectionHeader(t.accBills),
      if (bills.isEmpty)
        AppCard(child: Row(children: [
          const Icon(Icons.receipt_outlined, color: AppColors.textMuted),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: Text(t.accNoBillBecause('${e['noBillReason'] ?? ''}'))),
        ]))
      else
        SizedBox(
          height: 150,
          child: ListView(scrollDirection: Axis.horizontal, children: [
            for (final b in bills)
              Padding(
                padding: const EdgeInsets.only(right: AppSpacing.sm),
                child: InkWell(
                  onTap: () => _openBill(context, ref, b),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: SizedBox(
                      width: 112,
                      height: 150,
                      child: ColoredBox(
                        color: AppColors.primarySoft,
                        child: b['contentType'] == 'application/pdf'
                            ? const _PdfIcon()
                            : _NetBill(ngoId: ngoId, campaignId: campaignId, expenseId: id, attachmentId: '${b['id']}'),
                      ),
                    ),
                  ),
                ),
              ),
          ]),
        ),
      const SizedBox(height: AppSpacing.md),
      AppCard(child: Column(children: [
        if (e['voucherNo'] != null) KeyValueRow(t.accVoucher, '${e['voucherNo']}'),
        KeyValueRow(t.accBillDate, _day(e['date'])),
        KeyValueRow(t.accCategory, '${e['category']}'),
        KeyValueRow(t.accPaidTo, '${e['vendorName'] ?? '—'}'),
        if (e['billNumber'] != null) KeyValueRow(t.accBillNumber, '${e['billNumber']}'),
        KeyValueRow(t.accHowPaid, paymentModeLabel(t, e['paymentMode'] as String?)),
        KeyValueRow(t.accWhoPaid, e['paidBy'] == 'STAFF' ? who(e['paidByUser']) : t.accPaidByNgo),
        KeyValueRow(t.accAddedBy, '${who(e['submittedBy'])} · ${formatDate(e['submittedAt'])}'),
        if (status == ExpenseStatus.approved || status == ExpenseStatus.voided)
          KeyValueRow(t.accApprovedBy, '${who(e['decidedBy'])} · ${formatDate(e['decidedAt'])}${e['selfApproved'] == true ? ' (${t.accSelfApproved})' : ''}'),
      ])),
      const SizedBox(height: AppSpacing.lg),
      for (final a in actions) Padding(padding: const EdgeInsets.only(bottom: AppSpacing.sm), child: a),
    ]);
  }

  Future<void> _openBill(BuildContext context, WidgetRef ref, Map<String, dynamic> b) async {
    final key = (ngoId, campaignId, '${e['id']}', '${b['id']}');
    if (b['contentType'] == 'application/pdf') {
      try {
        final bytes = await ref.read(billBytesProvider(key).future);
        final dir = await getTemporaryDirectory();
        final f = File('${dir.path}/bill-${b['id']}.pdf');
        await f.writeAsBytes(bytes, flush: true);
        await OpenFilex.open(f.path, type: 'application/pdf');
      } catch (err) {
        if (context.mounted) showSnack(context, ApiError.from(err).message, error: true);
      }
      return;
    }
    await Navigator.of(context).push(MaterialPageRoute<void>(
      fullscreenDialog: true,
      builder: (_) => Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(backgroundColor: Colors.black, foregroundColor: Colors.white),
        body: Center(
          child: InteractiveViewer(
            maxScale: 5,
            child: Consumer(builder: (c, r, _) => r.watch(billBytesProvider(key)).when(
                  loading: () => const CircularProgressIndicator(),
                  error: (_, __) => const Icon(Icons.broken_image_outlined, color: Colors.white),
                  data: (bytes) => Image.memory(bytes),
                )),
          ),
        ),
      ),
    ));
  }
}

class _Note extends StatelessWidget {
  const _Note({required this.icon, required this.color, required this.text, this.title});
  final IconData icon;
  final Color color;
  final String text;
  final String? title;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: AppSpacing.md),
        child: AppCard(
          color: color.withValues(alpha: 0.08),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Icon(icon, color: color),
            const SizedBox(width: AppSpacing.sm),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (title != null) Text(title!, style: TextStyle(fontWeight: FontWeight.w700, color: color)),
              Text(text),
            ])),
          ]),
        ),
      );
}

/// Reason dialog (send back / void): 5-500 characters.
Future<String?> askReason(BuildContext context, String title, String hint) {
  final t = L10n.of(context);
  final ctl = TextEditingController();
  final key = GlobalKey<FormState>();
  return showDialog<String>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: Form(
        key: key,
        child: TextFormField(
          controller: ctl,
          autofocus: true,
          maxLines: 3,
          maxLength: 500,
          decoration: InputDecoration(hintText: hint),
          validator: V(t).text(min: 5, max: 500),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: Text(t.cancel)),
        FilledButton(onPressed: () { if (key.currentState!.validate()) Navigator.pop(c, ctl.text.trim()); }, child: Text(t.send)),
      ],
    ),
  );
}

// ============================================================ approval inbox (administrators)

class PendingExpensesPage extends ConsumerWidget {
  const PendingExpensesPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(pendingExpensesProvider(ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.accToApprove)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(pendingExpensesProvider(ngoId)),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message)]),
          data: (p) {
            final items = (p['items'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
            return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              if (items.isEmpty)
                EmptyState(icon: Icons.task_alt, title: t.accNothingToApprove)
              else ...[
                Text(t.accPendingLine(items.length, formatInr(p['total'], whole: true)), style: const TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: AppSpacing.sm),
                for (final e in items) ExpenseTile(ngoId: ngoId, expense: e, showCampaign: true),
              ],
            ]);
          },
        ),
      ),
    );
  }
}
