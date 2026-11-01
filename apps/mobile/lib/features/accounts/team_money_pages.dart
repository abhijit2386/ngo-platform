import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../campaigns/campaign_api.dart';
import 'accounts_api.dart';
import 'expense_pages.dart' show VendorFormSheet;
import 'sheet_pages.dart' show staffPosition;

// ============================================================ vendors

class VendorsPage extends ConsumerStatefulWidget {
  const VendorsPage({super.key});
  @override
  ConsumerState<VendorsPage> createState() => _VendorsPageState();
}

class _VendorsPageState extends ConsumerState<VendorsPage> {
  String _q = '';

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final m = ref.watch(currentMembershipProvider);
    final canEdit = m?.can('EXPENSE_RECORD') ?? false;
    final data = ref.watch(vendorsProvider(ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.accVendors), actions: [
        PopupMenuButton<String>(
          tooltip: t.accMoreOptions,
          onSelected: (v) => switch (v) {
            'import' => _import(ngoId),
            'template' => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/vendors/template.csv', 'vendors-template.csv', 'text/csv'),
            _ => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/vendors/export.csv', 'vendors.csv', 'text/csv'),
          },
          itemBuilder: (_) => [
            if (canEdit) PopupMenuItem(value: 'import', child: ListTile(leading: const Icon(Icons.upload_file), title: Text(t.accImportCsv))),
            PopupMenuItem(value: 'template', child: ListTile(leading: const Icon(Icons.description_outlined), title: Text(t.accCsvTemplate))),
            PopupMenuItem(value: 'export', child: ListTile(leading: const Icon(Icons.download_outlined), title: Text(t.accExportCsv))),
          ],
        ),
      ]),
      floatingActionButton: canEdit
          ? FloatingActionButton.extended(
              onPressed: () => showModalBottomSheet<void>(context: context, isScrollControlled: true, showDragHandle: true,
                  builder: (_) => VendorFormSheet(ngoId: ngoId)),
              icon: const Icon(Icons.add_business_outlined),
              label: Text(t.accNewVendor),
            )
          : null,
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(vendorsProvider(ngoId)),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message)]),
          data: (all) {
            final list = all.where((v) => _q.isEmpty || '${v['name']} ${v['category'] ?? ''} ${v['phone'] ?? ''}'.toLowerCase().contains(_q)).toList();
            return ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 96), children: [
              TextField(
                decoration: InputDecoration(prefixIcon: const Icon(Icons.search), hintText: t.accSearchVendors),
                onChanged: (v) => setState(() => _q = v.trim().toLowerCase()),
              ),
              const SizedBox(height: AppSpacing.sm),
              Text(t.accVendorsHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
              const SizedBox(height: AppSpacing.sm),
              if (all.isEmpty)
                EmptyState(icon: Icons.storefront_outlined, title: t.accNoVendors, message: t.accNoVendorsHint)
              else
                for (final v in list)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                    child: AppCard(
                      onTap: canEdit
                          ? () => showModalBottomSheet<void>(context: context, isScrollControlled: true, showDragHandle: true,
                              builder: (_) => VendorFormSheet(ngoId: ngoId, vendor: v))
                          : null,
                      child: Row(children: [
                        CircleAvatar(
                          backgroundColor: AppColors.primarySoft,
                          child: Text('${v['name']}'.characters.first.toUpperCase(), style: const TextStyle(color: AppColors.primary, fontWeight: FontWeight.w700)),
                        ),
                        const SizedBox(width: AppSpacing.md),
                        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text('${v['name']}', style: TextStyle(fontWeight: FontWeight.w600,
                              color: v['active'] == true ? AppColors.text : AppColors.textMuted)),
                          Text([v['category'], v['phone'], if (v['gstin'] != null) 'GSTIN ${v['gstin']}'].whereType<String>().join(' · '),
                              style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                          if (v['active'] != true) Text(t.accVendorInactive, style: const TextStyle(color: AppColors.error, fontSize: 12)),
                        ])),
                      ]),
                    ),
                  ),
            ]);
          },
        ),
      ),
    );
  }

  Future<void> _import(String ngoId) async {
    final t = L10n.of(context);
    final f = await FilePicker.pickFile(type: FileType.custom, allowedExtensions: ['csv', 'txt']);
    if (f == null) return;
    try {
      final bytes = await f.readAsBytes();
      final r = await ref.read(apiProvider).upload('/api/v1/ngos/$ngoId/vendors/import', bytes: bytes, filename: f.name);
      ref.invalidate(vendorsProvider(ngoId));
      if (!mounted) return;
      final errors = (r['errors'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
      await showDialog<void>(
        context: context,
        builder: (c) => AlertDialog(
          title: Text(t.accImportDone),
          content: SingleChildScrollView(child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            Text(t.accImportResult(r['created'] as int? ?? 0, r['skipped'] as int? ?? 0)),
            if (errors.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(t.accImportErrors(errors.length), style: const TextStyle(color: AppColors.error, fontWeight: FontWeight.w600)),
              for (final e in errors.take(20)) Text(t.accImportLine(e['line'] as int? ?? 0, '${e['message']}'), style: const TextStyle(fontSize: 13)),
            ],
          ])),
          actions: [FilledButton(onPressed: () => Navigator.pop(c), child: Text(t.ok))],
        ),
      );
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

// ============================================================ staff cash

/// Administrators: everyone's position (advances held / money owed). Other members go straight to their own ledger.
class StaffMoneyPage extends ConsumerWidget {
  const StaffMoneyPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final m = ref.watch(currentMembershipProvider);
    if (!(m?.isAdmin ?? false)) return const StaffLedgerPage(userId: 'me');
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final data = ref.watch(staffBalancesProvider(ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.accStaffCash)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(staffBalancesProvider(ngoId)),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message)]),
          data: (rows) {
            final byUser = {for (final r in rows) '${r['userId']}': r};
            final people = {...names.keys, ...byUser.keys}.toList()
              ..sort((a, b) => (names[a] ?? a).compareTo(names[b] ?? b));
            final held = rows.fold<num>(0, (a, r) => a + (amountOf(r['balance']) < 0 ? -amountOf(r['balance']) : 0));
            final owed = rows.fold<num>(0, (a, r) => a + (amountOf(r['balance']) > 0 ? amountOf(r['balance']) : 0));
            return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              Text(t.accStaffCashHint, style: const TextStyle(color: AppColors.textMuted)),
              const SizedBox(height: AppSpacing.md),
              Row(children: [
                Expanded(child: StatTile(label: t.accHeldByStaff, value: formatInr(held, whole: true), icon: Icons.account_balance_wallet_outlined, color: const Color(0xFFB45309))),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: StatTile(label: t.accOwedToStaff, value: formatInr(owed, whole: true), icon: Icons.reply, color: AppColors.growth)),
              ]),
              const SizedBox(height: AppSpacing.md),
              for (final u in people)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                  child: AppCard(
                    onTap: () => context.push('/ngo/staff-money/$u'),
                    child: Row(children: [
                      Expanded(child: Text(names[u] ?? t.aTeamMember, style: const TextStyle(fontWeight: FontWeight.w600))),
                      Text(staffPosition(t, amountOf(byUser[u]?['balance'])),
                          style: TextStyle(color: amountOf(byUser[u]?['balance']) < 0 ? const Color(0xFFB45309) : AppColors.textMuted)),
                      const Icon(Icons.chevron_right),
                    ]),
                  ),
                ),
            ]);
          },
        ),
      ),
    );
  }
}

class StaffLedgerPage extends ConsumerWidget {
  const StaffLedgerPage({super.key, required this.userId});
  final String userId; // 'me' for the signed-in member
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final m = ref.watch(currentMembershipProvider);
    final me = ref.watch(meProvider).value;
    final admin = m?.isAdmin ?? false;
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final self = userId == 'me' || userId == me?.userId;
    final data = ref.watch(staffLedgerProvider((ngoId, userId)));
    return Scaffold(
      appBar: AppBar(title: Text(self ? t.accMyAdvances : (names[userId] ?? t.accStaffCash))),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(staffLedgerProvider((ngoId, userId))),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message)]),
          data: (l) {
            final balance = amountOf(l['balance']);
            final entries = (l['entries'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
            final uid = '${l['userId']}';
            return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              AppCard(
                color: (balance < 0 ? const Color(0xFFB45309) : AppColors.primary).withValues(alpha: 0.08),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(staffPosition(t, balance), style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 4),
                  Text(balance < 0 ? t.accHoldsHint : (balance > 0 ? t.accOwesHint : t.accSettledHint),
                      style: const TextStyle(color: AppColors.textMuted)),
                ]),
              ),
              if (admin) ...[
                const SizedBox(height: AppSpacing.md),
                Wrap(spacing: AppSpacing.sm, runSpacing: AppSpacing.sm, children: [
                  FilledButton.icon(onPressed: () => _record(context, ref, ngoId, uid, 'ADVANCE'), icon: const Icon(Icons.payments_outlined), label: Text(t.accGiveAdvance)),
                  if (balance < 0)
                    OutlinedButton.icon(onPressed: () => _record(context, ref, ngoId, uid, 'RETURN', max: -balance), icon: const Icon(Icons.undo), label: Text(t.accRecordReturn)),
                  if (balance > 0)
                    OutlinedButton.icon(onPressed: () => _record(context, ref, ngoId, uid, 'REIMBURSEMENT', max: balance), icon: const Icon(Icons.reply), label: Text(t.accReimburse)),
                ]),
              ],
              const SizedBox(height: AppSpacing.md),
              SectionHeader(t.accHistory),
              if (entries.isEmpty) Text(t.accNoEntries, style: const TextStyle(color: AppColors.textMuted)),
              for (final e in entries)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(amountOf(e['amount']) < 0 ? Icons.call_made : Icons.call_received,
                      color: amountOf(e['amount']) < 0 ? const Color(0xFFB45309) : AppColors.primary),
                  title: Text(moneyKindLabel(t, e['kind'] as String?)),
                  subtitle: Text([formatDate(e['date']), if (e['mode'] != null) paymentModeLabel(t, e['mode'] as String?), if (e['note'] != null) '${e['note']}'].join(' · '),
                      maxLines: 2, overflow: TextOverflow.ellipsis),
                  trailing: Text(formatInr(amountOf(e['amount']).abs()), style: const TextStyle(fontWeight: FontWeight.w600)),
                  onTap: e['expenseId'] != null && e['campaignId'] != null
                      ? () => context.push('/ngo/campaigns/${e['campaignId']}/expenses/${e['expenseId']}')
                      : null,
                ),
            ]);
          },
        ),
      ),
    );
  }

  Future<void> _record(BuildContext context, WidgetRef ref, String ngoId, String uid, String kind, {num? max}) async {
    final t = L10n.of(context);
    final r = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _MoneySheet(ngoId: ngoId, kind: kind, max: max),
    );
    if (r == null) return;
    try {
      await ref.read(apiProvider).post('/api/v1/ngos/$ngoId/staff-money', body: {'userId': uid, 'kind': kind, ...r});
      ref.invalidate(staffLedgerProvider);
      ref.invalidate(staffBalancesProvider(ngoId));
      ref.invalidate(accountSheetProvider);
      if (context.mounted) showSnack(context, t.accRecorded);
    } catch (e) {
      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

class _MoneySheet extends ConsumerStatefulWidget {
  const _MoneySheet({required this.ngoId, required this.kind, this.max});
  final String ngoId;
  final String kind;
  final num? max;
  @override
  ConsumerState<_MoneySheet> createState() => _MoneySheetState();
}

class _MoneySheetState extends ConsumerState<_MoneySheet> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _note = TextEditingController();
  String _mode = 'CASH';
  String? _campaignId;

  @override
  void dispose() {
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final campaigns = [
      ...?ref.watch(ngoCampaignsProvider(CampaignFilter(widget.ngoId, CampaignStatus.live))).value,
      ...?ref.watch(ngoCampaignsProvider(CampaignFilter(widget.ngoId, CampaignStatus.closed))).value,
    ];
    final title = switch (widget.kind) { 'ADVANCE' => t.accGiveAdvance, 'RETURN' => t.accRecordReturn, _ => t.accReimburse };
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Form(
        key: _form,
        child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(AppSpacing.md), children: [
          Text(title, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _amount,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: t.accAmount, prefixText: '₹ ',
                helperText: widget.max == null ? null : t.accAtMost(formatInr(widget.max))),
            validator: V(t).amount(min: 1, max: widget.max ?? 10000000),
          ),
          const SizedBox(height: AppSpacing.md),
          Wrap(spacing: 6, children: [
            for (final mode in paymentModes)
              ChoiceChip(label: Text(paymentModeLabel(t, mode)), selected: _mode == mode, onSelected: (_) => setState(() => _mode = mode)),
          ]),
          const SizedBox(height: AppSpacing.md),
          DropdownButtonFormField<String?>(
            initialValue: _campaignId,
            isExpanded: true,
            decoration: InputDecoration(labelText: t.accForCampaign),
            items: [
              DropdownMenuItem(value: null, child: Text(t.accNoCampaign)),
              for (final c in campaigns) DropdownMenuItem(value: '${c['id']}', child: Text('${c['title']}', overflow: TextOverflow.ellipsis)),
            ],
            onChanged: (v) => setState(() => _campaignId = v),
          ),
          const SizedBox(height: AppSpacing.md),
          TextFormField(controller: _note, maxLength: 300, decoration: InputDecoration(labelText: t.accNote),
              validator: V(t).text(max: 300, optional: true)),
          const SizedBox(height: AppSpacing.md),
          FilledButton(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () {
              if (!_form.currentState!.validate()) return;
              Navigator.pop(context, {
                'amount': _amount.text.trim(),
                'mode': _mode,
                'date': isoDay(today()),
                if (_campaignId != null) 'campaignId': _campaignId,
                if (_note.text.trim().isNotEmpty) 'note': _note.text.trim(),
              });
            },
            child: Text(t.save),
          ),
        ]),
      ),
    );
  }
}
