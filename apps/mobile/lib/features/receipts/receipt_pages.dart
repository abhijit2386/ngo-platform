import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../accounts/accounts_api.dart' show downloadAndOffer;
import 'receipts_api.dart';

String _day(Object? iso) => iso == null ? '' : formatDate(iso);

String _when(Object? iso) {
  final d = DateTime.tryParse('${iso ?? ''}');
  return d == null ? '' : DateFormat('d MMM yyyy, h:mm a').format(d.toLocal());
}

// ============================================================================ NGO: register (REC-001)

class ReceiptsPage extends ConsumerStatefulWidget {
  const ReceiptsPage({super.key});
  @override
  ConsumerState<ReceiptsPage> createState() => _ReceiptsPageState();
}

class _ReceiptsPageState extends ConsumerState<ReceiptsPage> {
  final _search = TextEditingController();
  ReceiptQuery _q = ReceiptQuery(fy: currentFy());

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final m = ref.watch(currentMembershipProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final summary = ref.watch(receiptSummaryProvider((ngoId, _q.fy))).value;
    final data = ref.watch(receiptSearchProvider((ngoId, _q)));
    final years = <String>{currentFy(), ...((summary?['fiscalYears'] as List?) ?? const []).map((e) => '$e')}.toList()..sort((a, b) => b.compareTo(a));
    return Scaffold(
      appBar: AppBar(title: Text(t.rcpTitle), actions: [
        IconButton(
          tooltip: t.rcpSettings,
          icon: const Icon(Icons.tune),
          onPressed: () => context.push('/ngo/receipts/settings'),
        ),
      ]),
      body: RefreshIndicator(
        onRefresh: () async => invalidateReceipts(ref, ngoId),
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          if (summary != null) _SummaryCard(summary: summary),
          if ((summary?['failed'] as num? ?? 0) > 0 && (m?.can('RECEIPT_MANAGE') ?? false))
            Padding(
              padding: const EdgeInsets.only(top: AppSpacing.sm),
              child: AppCard(
                color: const Color(0xFFFDECEC),
                onTap: () => setState(() => _q = ReceiptQuery(q: _q.q, group: 'FAILED', fy: null)),
                child: Row(children: [
                  const Icon(Icons.error_outline, color: AppColors.error),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: Text(t.rcpFailedCount((summary!['failed'] as num).toInt()))),
                  const Icon(Icons.chevron_right),
                ]),
              ),
            ),
          const SizedBox(height: AppSpacing.md),
          TextField(
            controller: _search,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.search),
              hintText: t.rcpSearchHint,
              suffixIcon: _search.text.isEmpty
                  ? null
                  : IconButton(icon: const Icon(Icons.clear), onPressed: () {
                      _search.clear();
                      setState(() => _q = ReceiptQuery(q: '', group: _q.group, fy: _q.fy));
                    }),
            ),
            onSubmitted: (v) => setState(() => _q = ReceiptQuery(q: v.trim(), group: _q.group, fy: _q.fy)),
          ),
          const SizedBox(height: AppSpacing.sm),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: [
              PopupMenuButton<String>(
                initialValue: _q.fy ?? '',
                onSelected: (v) => setState(() => _q = ReceiptQuery(q: _q.q, group: _q.group, fy: v.isEmpty ? null : v)),
                itemBuilder: (_) => [
                  PopupMenuItem(value: '', child: Text(t.rcpAllYears)),
                  for (final y in years) PopupMenuItem(value: y, child: Text(t.rcpFy(y))),
                ],
                child: Chip(avatar: const Icon(Icons.calendar_month, size: 18), label: Text(_q.fy == null ? t.rcpAllYears : t.rcpFy(_q.fy!))),
              ),
              const SizedBox(width: 8),
              ChoiceChip(label: Text(t.rcpAll), selected: _q.group == null,
                  onSelected: (_) => setState(() => _q = ReceiptQuery(q: _q.q, fy: _q.fy))),
              for (final g in receiptGroups) ...[
                const SizedBox(width: 8),
                ChoiceChip(label: Text(receiptGroupLabel(t, g)), selected: _q.group == g,
                    onSelected: (_) => setState(() => _q = ReceiptQuery(q: _q.q, group: g, fy: _q.fy))),
              ],
            ]),
          ),
          const SizedBox(height: AppSpacing.sm),
          ...data.when(
            loading: () => [const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()))],
            error: (e, _) => [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(receiptSearchProvider))],
            data: (page) {
              final items = (page['items'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
              if (items.isEmpty) {
                return [EmptyState(icon: Icons.receipt_long_outlined, title: t.rcpEmpty, message: t.rcpEmptyHint)];
              }
              return [
                Text(t.rcpCount((page['totalItems'] as num? ?? items.length).toInt()), style: const TextStyle(color: AppColors.textMuted)),
                const SizedBox(height: AppSpacing.sm),
                for (final r in items) ReceiptTile(receipt: r, onTap: () => context.push('/ngo/receipts/${r['id']}')),
              ];
            },
          ),
        ]),
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({required this.summary});
  final Map<String, dynamic> summary;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return AppCard(
      color: AppColors.primarySoft,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(t.rcpFy('${summary['fiscalYear']}'), style: const TextStyle(color: AppColors.textMuted)),
        const SizedBox(height: 4),
        Row(children: [
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${summary['issued'] ?? 0}', style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
            Text(t.rcpIssuedLabel, style: const TextStyle(color: AppColors.textMuted)),
          ])),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(formatInr(summary['issuedAmount'], whole: true), style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
            Text(t.rcpAmountLabel, style: const TextStyle(color: AppColors.textMuted)),
          ])),
        ]),
        const SizedBox(height: AppSpacing.sm),
        Wrap(spacing: 8, runSpacing: 6, children: [
          StatusBadge(t.rcp80gCount((summary['issued80g'] as num? ?? 0).toInt()), tone: BadgeTone.info),
          if ((summary['pending'] as num? ?? 0) > 0) StatusBadge(t.rcpPreparingCount((summary['pending'] as num).toInt())),
          if ((summary['cancelled'] as num? ?? 0) > 0)
            StatusBadge(t.rcpCancelledCount((summary['cancelled'] as num).toInt()), color: AppColors.textMuted),
        ]),
        if (summary['lastNumber'] != null) ...[
          const SizedBox(height: AppSpacing.sm),
          Text(t.rcpLastNumber('${summary['lastNumber']}'), style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
        ],
      ]),
    );
  }
}

class ReceiptTile extends StatelessWidget {
  const ReceiptTile({super.key, required this.receipt, this.onTap, this.showNgo = false});
  final Map<String, dynamic> receipt;
  final VoidCallback? onTap;
  final bool showNgo;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final r = receipt;
    final cancelled = ReceiptStatus.cancelled(r['status']);
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        onTap: onTap,
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(
            width: 44, height: 44,
            decoration: BoxDecoration(color: AppColors.primarySoft, borderRadius: BorderRadius.circular(12)),
            child: Icon(cancelled ? Icons.receipt_long_outlined : Icons.receipt_long, color: cancelled ? AppColors.textMuted : AppColors.primary),
          ),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(showNgo ? '${r['ngoName'] ?? ''}' : '${r['donorName'] ?? t.rcpDonor}',
                style: TextStyle(fontWeight: FontWeight.w600, decoration: cancelled ? TextDecoration.lineThrough : null)),
            Text('${r['receiptNumber'] ?? t.rcpStatusPreparing}', style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
            Text([
              _day(r['donationDate'] ?? r['createdAt']),
              channelLabel(t, r['channel']),
              if (r['campaignTitle'] != null) '${r['campaignTitle']}',
            ].where((s) => s.isNotEmpty).join(' · '), style: const TextStyle(color: AppColors.textMuted, fontSize: 13),
                maxLines: 1, overflow: TextOverflow.ellipsis),
          ])),
          Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text(formatInr(r['amount']), style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            StatusBadge(receiptStatusLabel(t, r['status']), color: receiptStatusColor(r['status'])),
            if (r['tax80g'] == true) const Padding(padding: EdgeInsets.only(top: 4), child: StatusBadge('80G', tone: BadgeTone.info)),
          ]),
        ]),
      ),
    );
  }
}

// ============================================================================ NGO: receipt detail (REC-002..007)

class ReceiptDetailPage extends ConsumerWidget {
  const ReceiptDetailPage({super.key, required this.receiptId});
  final String receiptId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final m = ref.watch(currentMembershipProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(receiptDetailProvider((ngoId, receiptId)));
    return Scaffold(
      appBar: AppBar(title: Text(t.rcpReceipt)),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(receiptDetailProvider((ngoId, receiptId)))),
        data: (d) {
          final r = Map<String, dynamic>.from(d['receipt'] as Map);
          final history = (d['history'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
          final issued = ReceiptStatus.issued(r['status']);
          final manage = m?.can('RECEIPT_MANAGE') ?? false;
          final base = '/api/v1/ngos/$ngoId/receipts/$receiptId';
          Future<void> act(Future<void> Function() f, String done) async {
            try {
              await f();
              invalidateReceipts(ref, ngoId, receiptId);
              if (context.mounted) showSnack(context, done);
            } catch (e) {
              if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
            }
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(receiptDetailProvider((ngoId, receiptId))),
            child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              AppCard(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(child: Text('${r['receiptNumber'] ?? t.rcpStatusPreparing}',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700))),
                    StatusBadge(receiptStatusLabel(t, r['status']), color: receiptStatusColor(r['status'])),
                  ]),
                  const SizedBox(height: AppSpacing.sm),
                  Text(formatInr(r['amount']), style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800, color: AppColors.primary)),
                  Text('${r['donorName'] ?? ''}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                  if (r['tax80g'] == true) const Padding(padding: EdgeInsets.only(top: 6), child: StatusBadge('80G', tone: BadgeTone.info)),
                ]),
              ),
              if (r['status'] == ReceiptStatus.failed && r['lastError'] != null)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.sm),
                  child: AppCard(color: const Color(0xFFFDECEC), child: Text(t.rcpFailedWhy('${r['lastError']}'))),
                ),
              if (r['voidReason'] != null)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.sm),
                  child: AppCard(color: const Color(0xFFFDECEC), child: Text(t.rcpCancelledWhy('${r['voidReason']}'))),
                ),
              if (d['replacedBy'] != null)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.arrow_forward, color: AppColors.primary),
                  title: Text(t.rcpReplacedBy('${(d['replacedBy'] as Map)['receiptNumber']}')),
                  onTap: () => context.push('/ngo/receipts/${(d['replacedBy'] as Map)['id']}'),
                ),
              if (d['replaces'] != null)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.history, color: AppColors.textMuted),
                  title: Text(t.rcpReplaces('${(d['replaces'] as Map)['receiptNumber']}')),
                  onTap: () => context.push('/ngo/receipts/${(d['replaces'] as Map)['id']}'),
                ),
              const SizedBox(height: AppSpacing.md),
              if (r['receiptNumber'] != null && r['status'] != ReceiptStatus.pending && r['status'] != ReceiptStatus.generating
                  && r['status'] != ReceiptStatus.failed)
                FilledButton.icon(
                  style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                  onPressed: () => downloadAndOffer(context, ref, '$base/pdf', receiptFileName(r['receiptNumber']), 'application/pdf'),
                  icon: const Icon(Icons.picture_as_pdf_outlined),
                  label: Text(t.rcpOpenPdf),
                ),
              const SizedBox(height: AppSpacing.md),
              AppCard(
                child: Column(children: [
                  KeyValueRow(t.rcpDate, _day(r['donationDate'])),
                  KeyValueRow(t.rcpPayment, channelLabel(t, r['channel'])),
                  if (r['campaignTitle'] != null) KeyValueRow(t.rcpTowards, '${r['campaignTitle']}'),
                  KeyValueRow(t.rcpDonorAccount, r['donorAccount'] == true ? t.rcpYes : t.rcpNoAccount),
                  if (r['verifyCode'] != null) KeyValueRow(t.rcpVerifyCode, '${r['verifyCode']}'),
                  if (r['issuedAt'] != null) KeyValueRow(t.rcpIssuedOn, _day(r['issuedAt'])),
                  if (r['deliveredAt'] != null) KeyValueRow(t.rcpSentOn, _day(r['deliveredAt'])),
                ]),
              ),
              if (manage && issued) ...[
                const SizedBox(height: AppSpacing.md),
                OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                  onPressed: () => act(() => ref.read(apiProvider).post('$base/resend'), t.rcpResent),
                  icon: const Icon(Icons.forward_to_inbox_outlined),
                  label: Text(t.rcpResend),
                ),
                const SizedBox(height: AppSpacing.sm),
                OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                  onPressed: () async {
                    final reason = await _askReason(context);
                    if (reason == null) return;
                    try {
                      final n = await ref.read(apiProvider).post('$base/reissue', body: {'reason': reason});
                      invalidateReceipts(ref, ngoId, receiptId);
                      if (context.mounted) {
                        showSnack(context, t.rcpReissued);
                        context.pushReplacement('/ngo/receipts/${n['id']}');
                      }
                    } catch (e) {
                      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                    }
                  },
                  icon: const Icon(Icons.autorenew),
                  label: Text(t.rcpReissue),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(t.rcpReissueHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                ),
              ],
              if (manage && r['status'] == ReceiptStatus.failed) ...[
                const SizedBox(height: AppSpacing.md),
                BusyButton(label: t.rcpRetry, icon: Icons.refresh,
                    onPressed: () => act(() => ref.read(apiProvider).post('$base/retry'), t.rcpRetried)),
              ],
              const SizedBox(height: AppSpacing.lg),
              SectionHeader(t.rcpHistory),
              for (final h in history)
                // Plain row rather than a dense ListTile: a long detail (number, reason) wraps cleanly here.
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
                  child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    const Padding(
                      padding: EdgeInsets.only(top: 5, right: AppSpacing.md),
                      child: Icon(Icons.circle, size: 10, color: AppColors.primary),
                    ),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(_eventLabel(t, '${h['event']}'), style: Theme.of(context).textTheme.bodyMedium),
                        Text(_when(h['createdAt']), style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.textMuted)),
                        if (h['detail'] != null)
                          Text('${h['detail']}', style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.textMuted)),
                      ]),
                    ),
                  ]),
                ),
            ]),
          );
        },
      ),
    );
  }

  static String _eventLabel(L10n t, String e) => switch (e) {
        'CREATED' => t.rcpEvCreated,
        'NUMBERED' => t.rcpEvNumbered,
        'GENERATED' => t.rcpEvGenerated,
        'GENERATION_FAILED' => t.rcpEvFailed,
        'DELIVERY_REQUESTED' => t.rcpEvSending,
        'DELIVERED' => t.rcpEvDelivered,
        'RESENT' => t.rcpEvResent,
        'REISSUED' => t.rcpEvReissued,
        'VOIDED' => t.rcpEvVoided,
        _ => e,
      };

  static Future<String?> _askReason(BuildContext context) async {
    final t = L10n.of(context);
    final c = TextEditingController();
    final key = GlobalKey<FormState>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text(t.rcpReissue),
        content: Form(
          key: key,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(t.rcpReissueConfirm),
            const SizedBox(height: AppSpacing.sm),
            TextFormField(
              controller: c,
              maxLength: 300,
              decoration: InputDecoration(labelText: t.rcpReason, hintText: t.rcpReasonHint),
              validator: (v) => (v ?? '').trim().length < 5 ? t.rcpReasonTooShort : null,
            ),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d, false), child: Text(t.cancel)),
          FilledButton(onPressed: () { if (key.currentState!.validate()) Navigator.pop(d, true); }, child: Text(t.rcpReissue)),
        ],
      ),
    );
    final v = c.text.trim();
    c.dispose();
    return ok == true ? v : null;
  }
}

// ============================================================================ NGO: settings + signatories

class ReceiptSettingsPage extends ConsumerStatefulWidget {
  const ReceiptSettingsPage({super.key});
  @override
  ConsumerState<ReceiptSettingsPage> createState() => _ReceiptSettingsPageState();
}

class _ReceiptSettingsPageState extends ConsumerState<ReceiptSettingsPage> {
  final _form = GlobalKey<FormState>();
  final _prefix = TextEditingController();
  final _reg12a = TextEditingController();
  final _thanks = TextEditingController();
  int? _loadedVersion;

  @override
  void dispose() {
    for (final c in [_prefix, _reg12a, _thanks]) {
      c.dispose();
    }
    super.dispose();
  }

  void _fill(Map<String, dynamic> s) {
    final v = (s['version'] as num? ?? -1).toInt();
    if (_loadedVersion == v) return;
    _loadedVersion = v;
    _prefix.text = '${s['prefix'] ?? ''}';
    _reg12a.text = '${s['reg12aNumber'] ?? ''}';
    _thanks.text = '${s['thankYouNote'] ?? ''}';
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final m = ref.watch(currentMembershipProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final admin = m?.isAdmin ?? false;
    final settings = ref.watch(receiptSettingsProvider(ngoId));
    final signatories = ref.watch(signatoriesProvider(ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.rcpSettings)),
      body: settings.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(receiptSettingsProvider(ngoId))),
        data: (s) {
          _fill(s);
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            Text(t.rcpSettingsIntro, style: const TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: AppSpacing.md),
            Form(
              key: _form,
              child: Column(children: [
                TextFormField(
                  controller: _prefix,
                  enabled: admin,
                  textCapitalization: TextCapitalization.characters,
                  inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9]')), LengthLimitingTextInputFormatter(10)],
                  decoration: InputDecoration(
                      labelText: t.rcpPrefix,
                      helperMaxLines: 2,
                      helperText: t.rcpPrefixExample('${_prefix.text.toUpperCase()}/${currentFy()}/000001')),
                  onChanged: (_) => setState(() {}),
                  validator: (v) => RegExp(r'^[A-Za-z0-9]{2,10}$').hasMatch(v ?? '') ? null : t.rcpPrefixInvalid,
                ),
                const SizedBox(height: AppSpacing.sm),
                TextFormField(
                  controller: _reg12a,
                  enabled: admin,
                  maxLength: 60,
                  decoration: InputDecoration(labelText: t.rcp12a, helperText: t.rcpOptional),
                ),
                const SizedBox(height: AppSpacing.md),
                TextFormField(
                  controller: _thanks,
                  enabled: admin,
                  maxLength: 200,
                  maxLines: 2,
                  decoration: InputDecoration(labelText: t.rcpThanks),
                ),
              ]),
            ),
            if (admin)
              BusyButton(label: t.save, icon: Icons.save_outlined, onPressed: () async {
                if (!_form.currentState!.validate()) return;
                try {
                  await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/receipts/settings', body: {
                    'prefix': _prefix.text.trim().toUpperCase(),
                    'reg12aNumber': _reg12a.text.trim(),
                    'thankYouNote': _thanks.text.trim(),
                    'version': s['version'],
                  });
                  _loadedVersion = null;
                  ref.invalidate(receiptSettingsProvider(ngoId));
                  ref.invalidate(receiptSummaryProvider);
                  if (context.mounted) showSnack(context, t.rcpSaved);
                } catch (e) {
                  if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                }
              }),
            const SizedBox(height: AppSpacing.lg),
            SectionHeader(t.rcpSignatories, action: admin && (signatories.value?.length ?? 0) < 3
                ? TextButton.icon(onPressed: () => _editSignatory(context, ngoId, null), icon: const Icon(Icons.add), label: Text(t.rcpAdd))
                : null),
            Text(t.rcpSignatoriesHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
            const SizedBox(height: AppSpacing.sm),
            ...signatories.when(
              loading: () => [const LoadingView()],
              error: (e, _) => [Text(ApiError.from(e).message)],
              data: (list) => list.isEmpty
                  ? [Text(t.rcpNoSignatories, style: const TextStyle(color: AppColors.textMuted))]
                  : [for (final sg in list) _SignatoryTile(ngoId: ngoId, s: sg, admin: admin, onEdit: () => _editSignatory(context, ngoId, sg))],
            ),
            const SizedBox(height: AppSpacing.lg),
            AppCard(
              onTap: () => context.push('/ngo/receipts/design'),
              child: Row(children: [
                const Icon(Icons.design_services_outlined, color: AppColors.primary),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(t.rcdTitle, style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text(t.rcdEntryHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                ])),
                const StatusBadge('PRO', tone: BadgeTone.info),
                const Icon(Icons.chevron_right),
              ]),
            ),
            const SizedBox(height: AppSpacing.md),
            OutlinedButton.icon(
              style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
              onPressed: () => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/receipts/sample', 'sample-receipt.pdf', 'application/pdf'),
              icon: const Icon(Icons.preview_outlined),
              label: Text(t.rcpSample),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(t.rcpSampleHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
            ),
          ]);
        },
      ),
    );
  }

  Future<void> _editSignatory(BuildContext context, String ngoId, Map<String, dynamic>? s) async {
    final t = L10n.of(context);
    final name = TextEditingController(text: '${s?['name'] ?? ''}');
    final designation = TextEditingController(text: '${s?['designation'] ?? ''}');
    final key = GlobalKey<FormState>();
    final ok = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (c) => Padding(
        padding: EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg, MediaQuery.viewInsetsOf(c).bottom + AppSpacing.lg),
        child: Form(
          key: key,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(s == null ? t.rcpAddSignatory : t.rcpEditSignatory, style: Theme.of(c).textTheme.titleLarge),
            const SizedBox(height: AppSpacing.md),
            TextFormField(controller: name, maxLength: 80, textCapitalization: TextCapitalization.words,
                decoration: InputDecoration(labelText: t.rcpSignatoryName),
                validator: (v) => (v ?? '').trim().length < 2 ? t.rcpRequired : null),
            TextFormField(controller: designation, maxLength: 60, textCapitalization: TextCapitalization.words,
                decoration: InputDecoration(labelText: t.rcpDesignation, hintText: t.rcpDesignationHint),
                validator: (v) => (v ?? '').trim().length < 2 ? t.rcpRequired : null),
            const SizedBox(height: AppSpacing.sm),
            FilledButton(onPressed: () { if (key.currentState!.validate()) Navigator.pop(c, true); }, child: Text(t.save)),
          ]),
        ),
      ),
    );
    if (ok == true) {
      try {
        final body = {'name': name.text.trim(), 'designation': designation.text.trim(), if (s != null) 'version': s['version']};
        if (s == null) {
          await ref.read(apiProvider).post('/api/v1/ngos/$ngoId/receipts/signatories', body: body);
        } else {
          await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/receipts/signatories/${s['id']}', body: body);
        }
        ref.invalidate(signatoriesProvider(ngoId));
        if (context.mounted) showSnack(context, t.rcpSaved);
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
      }
    }
    name.dispose();
    designation.dispose();
  }
}

class _SignatoryTile extends ConsumerWidget {
  const _SignatoryTile({required this.ngoId, required this.s, required this.admin, required this.onEdit});
  final String ngoId;
  final Map<String, dynamic> s;
  final bool admin;
  final VoidCallback onEdit;

  Future<void> _upload(BuildContext context, WidgetRef ref, ImageSource? source) async {
    final t = L10n.of(context);
    late Uint8List bytes;
    String name = 'signature.png';
    if (source != null) {
      final x = await ImagePicker().pickImage(source: source, maxWidth: 2400, imageQuality: 92);
      if (x == null) return;
      bytes = await x.readAsBytes();
      name = x.name;
    } else {
      final f = await FilePicker.pickFile(type: FileType.custom, allowedExtensions: ['png', 'jpg', 'jpeg']);
      if (f == null) return;
      bytes = Uint8List.fromList(await f.readAsBytes());
      name = f.name;
    }
    final data = bytes;
    if (data.length > 1024 * 1024) {
      if (context.mounted) showSnack(context, t.rcpSignatureTooBig, error: true);
      return;
    }
    try {
      await ref.read(apiProvider).upload('/api/v1/ngos/$ngoId/receipts/signatories/${s['id']}/signature', bytes: data, filename: name);
      ref.invalidate(signatoriesProvider(ngoId));
      if (context.mounted) showSnack(context, t.rcpSignatureSaved);
    } catch (e) {
      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final has = s['hasSignature'] == true;
    final img = admin && has ? ref.watch(signatureImageProvider((ngoId, '${s['id']}', (s['version'] as num? ?? 0).toInt()))).value : null;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${s['name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
              Text('${s['designation']}', style: const TextStyle(color: AppColors.textMuted)),
            ])),
            if (admin)
              PopupMenuButton<String>(
                onSelected: (v) async {
                  switch (v) {
                    case 'edit':
                      onEdit();
                    case 'camera':
                      await _upload(context, ref, ImageSource.camera);
                    case 'gallery':
                      await _upload(context, ref, ImageSource.gallery);
                    case 'file':
                      await _upload(context, ref, null);
                    case 'clear':
                      try {
                        await ref.read(apiProvider).delete('/api/v1/ngos/$ngoId/receipts/signatories/${s['id']}/signature');
                        ref.invalidate(signatoriesProvider(ngoId));
                      } catch (e) {
                        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                      }
                    case 'remove':
                      if (!await confirm(context, title: t.rcpRemoveSignatory, message: t.rcpRemoveSignatoryConfirm,
                          confirmLabel: t.rcpRemove, cancelLabel: t.cancel, destructive: true)) {
                        return;
                      }
                      try {
                        await ref.read(apiProvider).delete('/api/v1/ngos/$ngoId/receipts/signatories/${s['id']}');
                        ref.invalidate(signatoriesProvider(ngoId));
                      } catch (e) {
                        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                      }
                  }
                },
                itemBuilder: (_) => [
                  PopupMenuItem(value: 'edit', child: Text(t.rcpEditSignatory)),
                  PopupMenuItem(value: 'camera', child: Text(t.rcpSignCamera)),
                  PopupMenuItem(value: 'gallery', child: Text(t.rcpSignGallery)),
                  PopupMenuItem(value: 'file', child: Text(t.rcpSignFile)),
                  if (has) PopupMenuItem(value: 'clear', child: Text(t.rcpSignRemove)),
                  PopupMenuItem(value: 'remove', child: Text(t.rcpRemoveSignatory)),
                ],
              ),
          ]),
          const SizedBox(height: AppSpacing.sm),
          Container(
            height: 70,
            alignment: Alignment.center,
            decoration: BoxDecoration(border: Border.all(color: AppColors.outline), borderRadius: BorderRadius.circular(10)),
            child: img != null
                ? Padding(padding: const EdgeInsets.all(6), child: Image.memory(Uint8List.fromList(img), fit: BoxFit.contain))
                : Text(has ? t.rcpSignatureOnFile : t.rcpNoSignature, style: const TextStyle(color: AppColors.textMuted)),
          ),
        ]),
      ),
    );
  }
}

// ============================================================================ donor: my receipts (DAPP-019..021)

class MyReceiptsPage extends ConsumerWidget {
  const MyReceiptsPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(myReceiptsProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t.rcpMine)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(myReceiptsProvider),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(myReceiptsProvider))]),
          data: (list) => list.isEmpty
              ? ListView(children: [EmptyState(icon: Icons.receipt_long_outlined, title: t.rcpMineEmpty, message: t.rcpMineEmptyHint)])
              : ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
                  for (final r in list) ReceiptTile(receipt: r, showNgo: true, onTap: () => context.push('/donor/receipts/${r['id']}')),
                ]),
        ),
      ),
    );
  }
}

class DonorReceiptPage extends ConsumerWidget {
  const DonorReceiptPage({super.key, required this.receiptId});
  final String receiptId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(myReceiptsProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t.rcpReceipt)),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(myReceiptsProvider)),
        data: (list) {
          final r = list.where((x) => x['id'] == receiptId).firstOrNull;
          if (r == null) return EmptyState(icon: Icons.receipt_long_outlined, title: t.rcpNotFound);
          final cancelled = ReceiptStatus.cancelled(r['status']);
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            AppCard(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${r['ngoName'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18)),
                Text('${r['receiptNumber']}', style: const TextStyle(color: AppColors.textMuted)),
                const SizedBox(height: AppSpacing.sm),
                Text(formatInr(r['amount']), style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800, color: AppColors.primary)),
                Text('${r['donorName'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 6),
                Wrap(spacing: 8, children: [
                  StatusBadge(receiptStatusLabel(t, r['status']), color: receiptStatusColor(r['status'])),
                  if (r['tax80g'] == true) const StatusBadge('80G', tone: BadgeTone.info),
                ]),
              ]),
            ),
            if (cancelled)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.sm),
                child: AppCard(color: const Color(0xFFFDECEC), child: Text(t.rcpDonorCancelled)),
              ),
            const SizedBox(height: AppSpacing.md),
            AppCard(child: Column(children: [
              KeyValueRow(t.rcpDate, _day(r['donationDate'])),
              KeyValueRow(t.rcpPayment, channelLabel(t, r['channel'])),
              if (r['campaignTitle'] != null) KeyValueRow(t.rcpTowards, '${r['campaignTitle']}'),
            ])),
            const SizedBox(height: AppSpacing.md),
            FilledButton.icon(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
              onPressed: () => downloadAndOffer(context, ref, '/api/v1/receipts/$receiptId/pdf', receiptFileName(r['receiptNumber']), 'application/pdf'),
              icon: const Icon(Icons.picture_as_pdf_outlined),
              label: Text(t.rcpOpenPdf),
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(t.rcpDonorNameNote, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
          ]);
        },
      ),
    );
  }
}
