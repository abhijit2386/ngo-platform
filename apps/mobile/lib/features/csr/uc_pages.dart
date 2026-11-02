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
import '../accounts/accounts_api.dart';
import 'csr_api.dart';
import 'uc_api.dart';

/// Utilisation certificates of the NGO, or of one campaign (from its account sheet).
class UcListPage extends ConsumerWidget {
  const UcListPage({super.key, this.campaignId});
  final String? campaignId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(ucListProvider((ngoId, campaignId)));
    final enabled = data.value?['enabled'] == true;
    return Scaffold(
      appBar: AppBar(title: Text(t.ucTitle)),
      floatingActionButton: enabled
          ? FloatingActionButton.extended(
              onPressed: () async {
                final id = await showModalBottomSheet<String>(
                    context: context, isScrollControlled: true, builder: (_) => UcFormSheet(ngoId: ngoId, campaignId: campaignId));
                if (id != null && context.mounted) {
                  invalidateUcs(ref, ngoId);
                  context.push('/ngo/ucs/$id');
                }
              },
              icon: const Icon(Icons.add),
              label: Text(t.ucNew))
          : null,
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => invalidateUcs(ref, ngoId)),
        data: (d) {
          final items = csrMaps(d['items']);
          return RefreshIndicator(
            onRefresh: () async => invalidateUcs(ref, ngoId),
            child: ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 96), children: [
              AppCard(
                color: AppColors.primarySoft,
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    const Icon(Icons.verified_outlined, color: AppColors.primary),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(child: Text(t.ucIntroTitle, style: Theme.of(context).textTheme.titleMedium)),
                    const StatusBadge('ENTERPRISE', tone: BadgeTone.info),
                  ]),
                  const SizedBox(height: AppSpacing.sm),
                  Text(t.ucIntro),
                ]),
              ),
              if (!enabled) ...[
                const SizedBox(height: AppSpacing.md),
                AppCard(child: Text(t.ucEnterpriseOnly, style: const TextStyle(color: AppColors.textMuted))),
              ],
              SectionHeader(t.ucCertificates),
              if (items.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                  child: Text(t.ucNone, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted)),
                ),
              for (final u in items)
                AppCard(
                  onTap: () => context.push('/ngo/ucs/${u['id']}'),
                  child: Row(children: [
                    const CircleAvatar(backgroundColor: AppColors.primarySoft, child: Icon(Icons.verified_outlined, color: AppColors.primary)),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${u['funderName']}', style: Theme.of(context).textTheme.titleSmall, maxLines: 1, overflow: TextOverflow.ellipsis),
                        Text('${u['number']} · ${formatInr(u['grantAmount'], whole: true)}'),
                        Text('${u['campaignTitle']} · ${formatDate(u['periodFrom'])} – ${formatDate(u['periodTo'])}',
                            maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                      ]),
                    ),
                    const SizedBox(width: AppSpacing.sm),
                    StatusBadge(ucStatusLabel(t, u['status']), tone: ucStatusTone(u['status'])),
                  ]),
                ),
            ]),
          );
        },
      ),
    );
  }
}

/// New certificate, or the details of a draft.
class UcFormSheet extends ConsumerStatefulWidget {
  const UcFormSheet({super.key, required this.ngoId, this.campaignId, this.uc});
  final String ngoId;
  final String? campaignId;
  final Map<String, dynamic>? uc;

  @override
  ConsumerState<UcFormSheet> createState() => _UcFormSheetState();
}

class _UcFormSheetState extends ConsumerState<UcFormSheet> {
  final _form = GlobalKey<FormState>();
  late final _funder = TextEditingController(text: widget.uc?['funderName'] as String?);
  late final _ref = TextEditingController(text: widget.uc?['funderReference'] as String?);
  late final _csr1 = TextEditingController(text: widget.uc?['csr1Number'] as String?);
  late final _grant = TextEditingController(text: widget.uc == null ? '' : '${amountOf(widget.uc!['grantAmount']).round()}');
  late String? _campaign = (widget.uc?['campaignId'] as String?) ?? widget.campaignId;
  late DateTime? _received = DateTime.tryParse('${widget.uc?['grantReceivedOn']}');
  late DateTime? _from = DateTime.tryParse('${widget.uc?['periodFrom']}');
  late DateTime? _to = DateTime.tryParse('${widget.uc?['periodTo']}') ?? DateUtils.dateOnly(DateTime.now());
  List<Map<String, dynamic>> _campaigns = const [];

  @override
  void initState() {
    super.initState();
    if (widget.uc == null) {
      ref.read(apiProvider).getJson('/api/v1/ngos/${widget.ngoId}/campaigns', query: {'size': '100'}).then((r) {
        if (mounted) setState(() => _campaigns = csrMaps(r['items']));
      }).catchError((_) {});
    }
  }

  @override
  void dispose() {
    for (final c in [_funder, _ref, _csr1, _grant]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _pick(DateTime? current, ValueChanged<DateTime> set) async {
    final now = DateTime.now();
    final d = await showDatePicker(
        context: context, initialDate: current ?? now, firstDate: DateTime(now.year - 5), lastDate: DateUtils.dateOnly(now));
    if (d != null) setState(() => set(d));
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final editing = widget.uc != null;
    String day(DateTime? d) => d == null ? t.ucChooseDate : DateFormat('d MMM yyyy').format(d);
    Widget dateField(String label, DateTime? v, ValueChanged<DateTime> set) => InkWell(
          onTap: () => _pick(v, set),
          child: InputDecorator(
            decoration: InputDecoration(labelText: label, suffixIcon: const Icon(Icons.event)),
            child: Text(day(v)),
          ),
        );
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Form(
          key: _form,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
            Text(editing ? t.ucEdit : t.ucNew, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: AppSpacing.sm),
            if (!editing) Text(t.ucNewHint, style: const TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: AppSpacing.md),
            if (!editing && widget.campaignId == null)
              DropdownButtonFormField<String>(
                initialValue: _campaign,
                isExpanded: true,
                decoration: InputDecoration(labelText: t.ucCampaign),
                items: [for (final c in _campaigns) DropdownMenuItem(value: '${c['id']}', child: Text('${c['title']}', overflow: TextOverflow.ellipsis))],
                onChanged: (v) => setState(() => _campaign = v),
                validator: (v) => v == null ? t.ucCampaignRequired : null,
              ),
            TextFormField(
              controller: _funder,
              textCapitalization: TextCapitalization.words,
              maxLength: 160,
              decoration: InputDecoration(labelText: t.ucFunder, hintText: t.csrCompanyHint),
              validator: (v) => (v ?? '').trim().length < 2 ? t.ucFunderRequired : null,
            ),
            TextFormField(controller: _ref, maxLength: 120, decoration: InputDecoration(labelText: t.ucReference, helperText: t.ucReferenceHint)),
            TextFormField(controller: _csr1, maxLength: 40, decoration: InputDecoration(labelText: t.ucCsr1, helperText: t.csrOptional)),
            TextFormField(
              controller: _grant,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: t.ucGrant, prefixText: '₹ '),
              validator: (v) => (num.tryParse((v ?? '').replaceAll(',', '')) ?? 0) <= 0 ? t.ucGrantRequired : null,
            ),
            const SizedBox(height: AppSpacing.sm),
            dateField(t.ucReceivedOn, _received, (d) => _received = d),
            const SizedBox(height: AppSpacing.sm),
            Row(children: [
              Expanded(child: dateField(t.ucPeriodFrom, _from, (d) => _from = d)),
              const SizedBox(width: AppSpacing.sm),
              Expanded(child: dateField(t.ucPeriodTo, _to, (d) => _to = d)),
            ]),
            const SizedBox(height: AppSpacing.lg),
            BusyButton(
              label: editing ? t.save : t.ucPrepare,
              icon: editing ? Icons.save_outlined : Icons.calculate_outlined,
              onPressed: () async {
                if (!_form.currentState!.validate()) return;
                if (_received == null || _from == null || _to == null) {
                  showSnack(context, t.ucDatesRequired, error: true);
                  return;
                }
                final f = DateFormat('yyyy-MM-dd');
                final body = {
                  'campaignId': _campaign,
                  'funderName': _funder.text.trim(),
                  'funderReference': _ref.text.trim(),
                  'csr1Number': _csr1.text.trim(),
                  'grantAmount': _grant.text.replaceAll(',', '').trim(),
                  'grantReceivedOn': f.format(_received!),
                  'periodFrom': f.format(_from!),
                  'periodTo': f.format(_to!),
                  if (editing) 'version': widget.uc!['version'],
                };
                try {
                  final api = ref.read(apiProvider);
                  final r = editing
                      ? await api.put('/api/v1/ngos/${widget.ngoId}/ucs/${widget.uc!['id']}', body: body)
                      : await api.post('/api/v1/ngos/${widget.ngoId}/ucs', body: body);
                  if (context.mounted) Navigator.pop(context, '${r['id']}');
                } catch (e) {
                  if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                }
              },
            ),
          ]),
        ),
      ),
    );
  }
}

/// One certificate: figures, then the step it is at (draft, waiting for the CA, certified, cancelled).
class UcPage extends ConsumerWidget {
  const UcPage({super.key, required this.ucId});
  final String ucId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(ucProvider((ngoId, ucId)));
    final uc = data.value;
    final draft = uc?['status'] == 'DRAFT';
    return Scaffold(
      appBar: AppBar(title: Text('${uc?['number'] ?? t.ucTitle}'), actions: [
        if (uc != null)
          IconButton(
            tooltip: t.ucPdf,
            icon: const Icon(Icons.picture_as_pdf_outlined),
            onPressed: () => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/ucs/$ucId/pdf',
                '${'${uc['number']}'.replaceAll('/', '-')}${uc['status'] == 'CA_CERTIFIED' ? '' : '-draft'}.pdf', 'application/pdf'),
          ),
        if (draft)
          IconButton(
            tooltip: t.ucEdit,
            icon: const Icon(Icons.edit_outlined),
            onPressed: () async {
              final r = await showModalBottomSheet<String>(
                  context: context, isScrollControlled: true, builder: (_) => UcFormSheet(ngoId: ngoId, uc: uc));
              if (r != null) invalidateUcs(ref, ngoId, ucId);
            },
          ),
      ]),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => invalidateUcs(ref, ngoId, ucId)),
        data: (uc) => RefreshIndicator(
          onRefresh: () async => invalidateUcs(ref, ngoId, ucId),
          child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            _UcHeader(uc: uc),
            _Stage(uc: uc, ngoId: ngoId),
            _FiguresCard(uc: uc),
            _Heads(uc: uc),
            _Lines(uc: uc),
            const SizedBox(height: AppSpacing.xl),
          ]),
        ),
      ),
      bottomNavigationBar: uc == null ? null : _UcActions(uc: uc, ngoId: ngoId),
    );
  }
}

class _UcHeader extends StatelessWidget {
  const _UcHeader({required this.uc});
  final Map<String, dynamic> uc;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return AppCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('${uc['funderName']}', style: Theme.of(context).textTheme.titleLarge)),
          StatusBadge(ucStatusLabel(t, uc['status']), tone: ucStatusTone(uc['status'])),
        ]),
        const SizedBox(height: AppSpacing.xs),
        Text('${uc['campaignTitle']}'),
        Text(t.ucPeriod(formatDate(uc['periodFrom']), formatDate(uc['periodTo'])), style: const TextStyle(color: AppColors.textMuted)),
        if ((uc['funderReference'] ?? '').toString().isNotEmpty)
          Text(t.ucReferenceIs('${uc['funderReference']}'), style: const TextStyle(color: AppColors.textMuted)),
        Text(t.ucGrantReceived(formatInr(uc['grantAmount']), formatDate(uc['grantReceivedOn'])), style: const TextStyle(color: AppColors.textMuted)),
      ]),
    );
  }
}

class _Stage extends StatelessWidget {
  const _Stage({required this.uc, required this.ngoId});
  final Map<String, dynamic> uc;
  final String ngoId;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final s = uc['status'];
    final (Color color, IconData icon, String title, String text) = switch (s) {
      'DRAFT' => (const Color(0xFFB45309), Icons.edit_note, t.ucStageDraft, t.ucStageDraftText),
      'AWAITING_CA' => (AppColors.primary, Icons.hourglass_top, t.ucStageAwaiting, t.ucStageAwaitingText(formatDate(uc['caLinkExpiresAt']))),
      'CA_LINK_EXPIRED' => (AppColors.error, Icons.link_off, t.ucStageExpired, t.ucStageExpiredText),
      'CA_CERTIFIED' => (AppColors.success, Icons.verified, t.ucStageCertified,
          t.ucStageCertifiedText('${uc['caName']}', '${uc['caMembershipNo']}', '${uc['caUdin']}', formatDate(uc['caCertifiedAt']))),
      _ => (AppColors.error, Icons.block, t.ucStageCancelled, '${uc['cancelReason'] ?? ''}'),
    };
    return AppCard(
      color: color.withValues(alpha: 0.07),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(icon, color: color),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: Text(title, style: TextStyle(fontWeight: FontWeight.w700, color: color))),
        ]),
        const SizedBox(height: AppSpacing.xs),
        Text(text),
        if (uc['signatoryName'] != null)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text(t.ucSignedBy('${uc['signatoryName']}', '${uc['signatoryRole']}', formatDate(uc['ngoSignedAt'])),
                style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
          ),
        if (s == 'AWAITING_CA') ...[
          const SizedBox(height: AppSpacing.sm),
          SelectableText('${uc['caLink']}', style: const TextStyle(fontSize: 12, color: AppColors.primary)),
          const SizedBox(height: AppSpacing.sm),
          Row(children: [
            Expanded(
                child: FilledButton.icon(
                    onPressed: () => shareCaLink(context, uc), icon: const Icon(Icons.share_outlined), label: Text(t.ucSendToCa))),
            const SizedBox(width: AppSpacing.sm),
            Expanded(child: OutlinedButton.icon(onPressed: () => copyCaLink(context, uc), icon: const Icon(Icons.copy), label: Text(t.csrCopy))),
          ]),
        ],
      ]),
    );
  }
}

class _FiguresCard extends StatelessWidget {
  const _FiguresCard({required this.uc});
  final Map<String, dynamic> uc;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final f = Map<String, dynamic>.from(uc['figures'] as Map? ?? const {});
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SectionHeader(t.ucFigures),
      GridView.count(
        crossAxisCount: 2,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        mainAxisSpacing: AppSpacing.sm,
        crossAxisSpacing: AppSpacing.sm,
        childAspectRatio: 1.7,
        children: [
          StatTile(label: t.ucGrantShort, value: formatInr(f['grant'], whole: true), icon: Icons.south_west),
          StatTile(label: t.ucUtilised, value: formatInr(f['utilised'], whole: true), icon: Icons.north_east, color: AppColors.growth),
          StatTile(label: t.ucUnspent, value: formatInr(f['unspent'], whole: true), icon: Icons.account_balance_wallet_outlined),
          StatTile(label: t.ucOwnFunds, value: formatInr(f['ownFunds'], whole: true), icon: Icons.volunteer_activism_outlined,
              color: amountOf(f['ownFunds']) > 0 ? const Color(0xFFB45309) : AppColors.textMuted),
        ],
      ),
      const SizedBox(height: AppSpacing.xs),
      Text(t.ucFromAccounts((f['expenses'] as num? ?? 0).toInt(), formatInr(f['spent'])), style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
      if ((f['withoutBill'] as num? ?? 0) > 0)
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.xs),
          child: Text(t.ucWithoutBill((f['withoutBill'] as num).toInt()), style: const TextStyle(color: Color(0xFFB45309), fontSize: 12)),
        ),
      if (f['frozen'] != true)
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.xs),
          child: Text(t.ucLiveFigures, style: const TextStyle(color: AppColors.textMuted, fontSize: 12, fontStyle: FontStyle.italic)),
        ),
    ]);
  }
}

class _Heads extends StatelessWidget {
  const _Heads({required this.uc});
  final Map<String, dynamic> uc;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final heads = csrMaps((uc['figures'] as Map?)?['heads']);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SectionHeader(t.ucByHead),
      AppCard(
        child: Column(children: [
          for (final h in heads)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(children: [
                Expanded(child: Text('${h['head']}')),
                SizedBox(width: 92, child: Text(h['budget'] == null ? '—' : formatInr(h['budget'], whole: true), textAlign: TextAlign.right,
                    style: const TextStyle(color: AppColors.textMuted))),
                SizedBox(width: 92, child: Text(formatInr(h['spent'], whole: true), textAlign: TextAlign.right,
                    style: const TextStyle(fontWeight: FontWeight.w600))),
              ]),
            ),
        ]),
      ),
    ]);
  }
}

class _Lines extends StatelessWidget {
  const _Lines({required this.uc});
  final Map<String, dynamic> uc;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final lines = csrMaps((uc['figures'] as Map?)?['lines']);
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      title: Text(t.ucExpenses(lines.length), style: Theme.of(context).textTheme.titleMedium),
      children: [
        for (final l in lines)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: Text('${l['description']}', maxLines: 2, overflow: TextOverflow.ellipsis),
            subtitle: Text('${l['voucherNo'] ?? ''} · ${formatDate(l['date'])} · ${l['head']}${(l['bills'] as num? ?? 0) == 0 ? ' · ${t.ucNoBill}' : ''}'),
            trailing: Text(formatInr(l['amount']), style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
      ],
    );
  }
}

class _UcActions extends ConsumerWidget {
  const _UcActions({required this.uc, required this.ngoId});
  final Map<String, dynamic> uc;
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final s = uc['status'];
    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/ucs/${uc['id']}';
    Future<void> run(Future<Map<String, dynamic>> Function() call) async {
      try {
        await call();
        invalidateUcs(ref, ngoId, '${uc['id']}');
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
      }
    }

    Future<void> cancel() async {
      final reason = await _askReason(context);
      if (reason != null) await run(() => api.post('$base/cancel', body: {'reason': reason}));
    }

    final Widget? child = switch (s) {
      'DRAFT' => BusyButton(
          label: t.ucSign,
          icon: Icons.draw_outlined,
          onPressed: () async {
            final who = await _askSigner(context);
            if (who != null) {
              await run(() => api.post('$base/sign', body: {'signatoryName': who.$1, 'signatoryRole': who.$2, 'version': uc['version']}));
            }
          }),
      'AWAITING_CA' || 'CA_LINK_EXPIRED' => Row(children: [
          Expanded(child: OutlinedButton.icon(onPressed: () => run(() => api.post('$base/ca-link')), icon: const Icon(Icons.refresh), label: Text(t.ucNewLink))),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
              child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(foregroundColor: AppColors.error), onPressed: cancel, icon: const Icon(Icons.block), label: Text(t.ucCancel))),
        ]),
      'CA_CERTIFIED' => OutlinedButton.icon(
          style: OutlinedButton.styleFrom(foregroundColor: AppColors.error), onPressed: cancel, icon: const Icon(Icons.block), label: Text(t.ucCancel)),
      _ => null,
    };
    if (child == null) return const SizedBox.shrink();
    return SafeArea(child: Padding(padding: const EdgeInsets.all(AppSpacing.md), child: child));
  }

  Future<(String, String)?> _askSigner(BuildContext context) {
    final t = L10n.of(context);
    final name = TextEditingController(), role = TextEditingController();
    return showDialog<(String, String)>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(t.ucSign),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(t.ucSignHint),
          TextField(controller: name, textCapitalization: TextCapitalization.words, decoration: InputDecoration(labelText: t.ucSignatory)),
          TextField(controller: role, textCapitalization: TextCapitalization.words, decoration: InputDecoration(labelText: t.ucDesignation)),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c), child: Text(t.cancel)),
          FilledButton(
            onPressed: () {
              if (name.text.trim().length >= 3 && role.text.trim().length >= 2) Navigator.pop(c, (name.text.trim(), role.text.trim()));
            },
            child: Text(t.ucSignNow),
          ),
        ],
      ),
    );
  }

  Future<String?> _askReason(BuildContext context) {
    final t = L10n.of(context);
    final reason = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(t.ucCancel),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(t.ucCancelHint),
          TextField(controller: reason, maxLength: 300, decoration: InputDecoration(labelText: t.ucCancelReason)),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c), child: Text(t.cancel)),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppColors.error),
            onPressed: () { if (reason.text.trim().length >= 5) Navigator.pop(c, reason.text.trim()); },
            child: Text(t.ucCancel),
          ),
        ],
      ),
    );
  }
}
