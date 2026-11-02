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
import 'csr_pages.dart' show formatDateTime;
import 'proposal_api.dart';
import 'uc_api.dart';
import 'uc_pages.dart';

final _day = DateFormat('yyyy-MM-dd');

Future<DateTime?> _pickDate(BuildContext context, DateTime? current, {DateTime? first, DateTime? last}) {
  final now = DateUtils.dateOnly(DateTime.now());
  return showDatePicker(
      context: context,
      initialDate: current ?? now,
      firstDate: first ?? DateTime(now.year - 1),
      lastDate: last ?? DateTime(now.year + 1, now.month, now.day));
}

Widget _dateField(BuildContext context, String label, DateTime? v, VoidCallback onTap) {
  final t = L10n.of(context);
  return InkWell(
    onTap: onTap,
    child: InputDecorator(
      decoration: InputDecoration(labelText: label, suffixIcon: const Icon(Icons.event)),
      child: Text(v == null ? t.ucChooseDate : DateFormat('d MMM yyyy').format(v)),
    ),
  );
}

// ==================================================================================================== list

class ProposalListPage extends ConsumerWidget {
  const ProposalListPage({super.key, this.campaignId});
  final String? campaignId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(proposalListProvider((ngoId, campaignId)));
    final enabled = data.value?['enabled'] == true;
    return Scaffold(
      appBar: AppBar(title: Text(t.prTitle)),
      floatingActionButton: enabled
          ? FloatingActionButton.extended(
              onPressed: () async {
                final id = await showModalBottomSheet<String>(
                    context: context, isScrollControlled: true, builder: (_) => ProposalNewSheet(ngoId: ngoId, campaignId: campaignId));
                if (id != null && context.mounted) {
                  invalidateProposals(ref, ngoId);
                  context.push('/ngo/proposals/$id');
                }
              },
              icon: const Icon(Icons.add),
              label: Text(t.prNew))
          : null,
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => invalidateProposals(ref, ngoId)),
        data: (d) {
          final items = csrMaps(d['items']);
          final s = Map<String, dynamic>.from(d['summary'] as Map? ?? const {});
          return RefreshIndicator(
            onRefresh: () async => invalidateProposals(ref, ngoId),
            child: ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 96), children: [
              AppCard(
                color: AppColors.primarySoft,
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    const Icon(Icons.campaign_outlined, color: AppColors.primary),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(child: Text(t.prIntroTitle, style: Theme.of(context).textTheme.titleMedium)),
                    const StatusBadge('ENTERPRISE', tone: BadgeTone.info),
                  ]),
                  const SizedBox(height: AppSpacing.sm),
                  Text(t.prIntro),
                ]),
              ),
              if (!enabled) ...[
                const SizedBox(height: AppSpacing.md),
                AppCard(child: Text(t.prEnterpriseOnly, style: const TextStyle(color: AppColors.textMuted))),
              ],
              if (items.isNotEmpty) ...[
                SectionHeader(t.prPipeline),
                Row(children: [
                  Expanded(child: _Tile(label: t.prOpen, value: '${s['open'] ?? 0}', sub: formatInr(s['askedOpen'], whole: true))),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: _Tile(label: t.prWon, value: '${s['funded'] ?? 0}', sub: formatInr(s['fundedTotal'], whole: true), color: AppColors.success)),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: _Tile(label: t.prSuccess, value: s['successPercent'] == null ? '—' : '${s['successPercent']}%', sub: t.prDecided(((s['funded'] as num? ?? 0) + (s['declined'] as num? ?? 0)).toInt()))),
                ]),
                if ((s['followUpsDue'] as num? ?? 0) > 0)
                  Padding(
                    padding: const EdgeInsets.only(top: AppSpacing.sm),
                    child: Row(children: [
                      const Icon(Icons.notifications_active_outlined, size: 18, color: Color(0xFFB45309)),
                      const SizedBox(width: AppSpacing.xs),
                      Text(t.prFollowUpsDue((s['followUpsDue'] as num).toInt()), style: const TextStyle(color: Color(0xFFB45309), fontWeight: FontWeight.w600)),
                    ]),
                  ),
              ],
              SectionHeader(t.prProposals),
              if (items.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                  child: Text(t.prNone, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted)),
                ),
              for (final p in items)
                AppCard(
                  onTap: () => context.push('/ngo/proposals/${p['id']}'),
                  child: Row(children: [
                    CircleAvatar(
                        backgroundColor: AppColors.primarySoft,
                        child: Icon(p['followUpDue'] == true ? Icons.notifications_active_outlined : Icons.campaign_outlined,
                            color: p['followUpDue'] == true ? const Color(0xFFB45309) : AppColors.primary)),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${p['funderName']}', style: Theme.of(context).textTheme.titleSmall, maxLines: 1, overflow: TextOverflow.ellipsis),
                        Text('${p['number']} · ${formatInr(p['status'] == 'FUNDED' ? p['fundedAmount'] : p['askAmount'], whole: true)}'),
                        Text(
                            p['followUpOn'] != null
                                ? t.prFollowUpOn(formatDate(p['followUpOn']))
                                : '${p['title']}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(color: p['followUpDue'] == true ? const Color(0xFFB45309) : AppColors.textMuted, fontSize: 12)),
                      ]),
                    ),
                    const SizedBox(width: AppSpacing.sm),
                    StatusBadge(proposalStatusLabel(t, p['status']), tone: proposalStatusTone(p['status'])),
                  ]),
                ),
            ]),
          );
        },
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.label, required this.value, required this.sub, this.color = AppColors.primary});
  final String label, value, sub;
  final Color color;

  @override
  Widget build(BuildContext context) => AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(value, style: Theme.of(context).textTheme.titleLarge?.copyWith(color: color)),
          Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12)),
          Text(sub, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.textMuted, fontSize: 11)),
        ]),
      );
}

// ==================================================================================================== new

class ProposalNewSheet extends ConsumerStatefulWidget {
  const ProposalNewSheet({super.key, required this.ngoId, this.campaignId});
  final String ngoId;
  final String? campaignId;

  @override
  ConsumerState<ProposalNewSheet> createState() => _ProposalNewSheetState();
}

class _ProposalNewSheetState extends ConsumerState<ProposalNewSheet> {
  final _form = GlobalKey<FormState>();
  final _funder = TextEditingController();
  final _ask = TextEditingController();
  late String? _campaign = widget.campaignId;
  List<Map<String, dynamic>> _campaigns = const [];

  @override
  void initState() {
    super.initState();
    ref.read(apiProvider).getJson('/api/v1/ngos/${widget.ngoId}/campaigns', query: {'size': '100'}).then((r) {
      if (mounted) setState(() => _campaigns = csrMaps(r['items']));
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _funder.dispose();
    _ask.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Form(
          key: _form,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
            Text(t.prNew, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: AppSpacing.sm),
            Text(t.prNewHint, style: const TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: AppSpacing.md),
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
              decoration: InputDecoration(labelText: t.prFunder, hintText: t.csrCompanyHint),
              validator: (v) => (v ?? '').trim().length < 2 ? t.ucFunderRequired : null,
            ),
            TextFormField(
              controller: _ask,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: t.prAsk, prefixText: '₹ ', helperText: t.prAskHint),
            ),
            const SizedBox(height: AppSpacing.lg),
            BusyButton(
              label: t.prStart,
              icon: Icons.edit_note,
              onPressed: () async {
                if (!_form.currentState!.validate()) return;
                final ask = _ask.text.replaceAll(',', '').trim();
                try {
                  final r = await ref.read(apiProvider).post('/api/v1/ngos/${widget.ngoId}/csr-proposals',
                      body: {'campaignId': _campaign, 'funderName': _funder.text.trim(), if (ask.isNotEmpty) 'askAmount': ask});
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

// ==================================================================================================== proposal

class ProposalPage extends ConsumerWidget {
  const ProposalPage({super.key, required this.proposalId});
  final String proposalId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(proposalProvider((ngoId, proposalId)));
    final p = data.value;
    return Scaffold(
      appBar: AppBar(title: Text('${p?['number'] ?? t.prTitle}'), actions: [
        if (p != null)
          IconButton(
            tooltip: t.ucPdf,
            icon: const Icon(Icons.picture_as_pdf_outlined),
            onPressed: () => downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/csr-proposals/$proposalId/pdf',
                '${'${p['number']}'.replaceAll('/', '-')}${p['status'] == 'DRAFT' ? '-draft' : ''}.pdf', 'application/pdf'),
          ),
        if (p?['status'] == 'DRAFT')
          IconButton(tooltip: t.prEdit, icon: const Icon(Icons.edit_outlined), onPressed: () => context.push('/ngo/proposals/$proposalId/edit')),
        if (p != null && p['status'] != 'DRAFT')
          IconButton(
            tooltip: t.prCopy,
            icon: const Icon(Icons.copy_all_outlined),
            onPressed: () async {
              try {
                final r = await ref.read(apiProvider).post('/api/v1/ngos/$ngoId/csr-proposals/$proposalId/copy');
                invalidateProposals(ref, ngoId);
                if (context.mounted) context.push('/ngo/proposals/${r['id']}');
              } catch (e) {
                if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
              }
            },
          ),
      ]),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => invalidateProposals(ref, ngoId, proposalId)),
        data: (p) => RefreshIndicator(
          onRefresh: () async => invalidateProposals(ref, ngoId, proposalId),
          child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            _Header(p: p),
            _Stage(p: p, ngoId: ngoId),
            _Story(p: p),
            _Progress(p: p),
            _Log(p: p),
            const SizedBox(height: AppSpacing.xl),
          ]),
        ),
      ),
      bottomNavigationBar: p == null ? null : _Actions(p: p, ngoId: ngoId),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.p});
  final Map<String, dynamic> p;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return AppCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('${p['funderName']}', style: Theme.of(context).textTheme.titleLarge)),
          StatusBadge(proposalStatusLabel(t, p['status']), tone: proposalStatusTone(p['status'])),
        ]),
        if (p['funderContact'] != null) Text(t.prAttn('${p['funderContact']}'), style: const TextStyle(color: AppColors.textMuted)),
        const SizedBox(height: AppSpacing.xs),
        Text('${p['title']}', style: const TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: AppSpacing.xs),
        Text(t.prAsking(formatInr(p['askAmount'], whole: true), (p['durationMonths'] as num? ?? 12).toInt())),
        if (p['scheduleViiLabel'] != null) Text('${p['scheduleViiLabel']}', style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        if (p['beneficiaries'] != null || p['location'] != null)
          Text([if (p['beneficiaries'] != null) t.prPeople((p['beneficiaries'] as num).toInt()), if (p['location'] != null) '${p['location']}'].join(' · '),
              style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
      ]),
    );
  }
}

class _Stage extends StatelessWidget {
  const _Stage({required this.p, required this.ngoId});
  final Map<String, dynamic> p;
  final String ngoId;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final s = p['status'];
    final missing = (p['missing'] as List? ?? const []).cast<Object>();
    final (Color color, IconData icon, String title, String text) = switch (s) {
      'DRAFT' => (const Color(0xFFB45309), Icons.edit_note, t.prStageDraft, missing.isEmpty ? t.prStageDraftReady : t.prStageDraftMissing),
      'SENT' => (AppColors.primary, Icons.outgoing_mail, t.prStageSent(formatDate(p['sentOn'])),
          p['followUpOn'] == null ? t.prStageSentText : t.prFollowUpOn(formatDate(p['followUpOn']))),
      'FUNDED' => (AppColors.success, Icons.verified, t.prStageFunded(formatInr(p['fundedAmount'], whole: true)),
          t.prStageFundedText(formatDate(p['fundedOn']), '${p['funderReference'] ?? '—'}')),
      'DECLINED' => (AppColors.error, Icons.cancel_outlined, t.prStatusDeclined, '${p['closeReason'] ?? ''}'),
      _ => (AppColors.textMuted, Icons.block, t.prStatusWithdrawn, '${p['closeReason'] ?? ''}'),
    };
    final ucs = csrMaps(p['certificates']);
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
        for (final m in missing)
          Padding(padding: const EdgeInsets.only(top: 2), child: Text('•  $m', style: const TextStyle(color: Color(0xFFB45309), fontSize: 12))),
        if (p['packUrl'] != null)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.sm),
            child: Row(children: [
              const Icon(Icons.folder_shared_outlined, size: 18, color: AppColors.textMuted),
              const SizedBox(width: AppSpacing.xs),
              Expanded(child: Text(t.prPackAttached('${p['packTitle'] ?? ''}', formatDate(p['packExpiresAt'])),
                  style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
            ]),
          )
        else if (s == 'DRAFT')
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.sm),
            child: Text(t.prNoPack, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
          ),
        for (final u in ucs)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.verified_outlined, color: AppColors.primary),
            title: Text('${u['number']}'),
            trailing: StatusBadge(ucStatusLabel(t, u['status']), tone: ucStatusTone(u['status'])),
            onTap: () => context.push('/ngo/ucs/${u['id']}'),
          ),
      ]),
    );
  }
}

class _Story extends StatelessWidget {
  const _Story({required this.p});
  final Map<String, dynamic> p;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final parts = [
      (t.prNeed, p['need']),
      (t.prObjectives, p['objectives']),
      (t.prActivities, p['activities']),
      (t.prOutcomes, p['outcomes']),
      (t.prMonitoring, p['monitoring']),
    ];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SectionHeader(t.prTheProposal),
      AppCard(
        child: Column(children: [
          for (final (title, body) in parts)
            ExpansionTile(
              tilePadding: EdgeInsets.zero,
              title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
              subtitle: Text(
                  '${body ?? ''}'.trim().isEmpty ? (title == t.prMonitoring ? t.prMonitoringDefault : t.prEmpty) : '${body ?? ''}'.trim().split('\n').first,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      color: '${body ?? ''}'.trim().isEmpty && title != t.prMonitoring ? const Color(0xFFB45309) : AppColors.textMuted, fontSize: 12)),
              children: [
                Align(alignment: Alignment.centerLeft, child: Padding(padding: const EdgeInsets.only(bottom: AppSpacing.sm), child: Text('${body ?? ''}'))),
              ],
            ),
        ]),
      ),
    ]);
  }
}

class _Progress extends StatelessWidget {
  const _Progress({required this.p});
  final Map<String, dynamic> p;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final g = Map<String, dynamic>.from(p['progress'] as Map? ?? const {});
    final budget = csrMaps(g['budget']);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SectionHeader(t.prProgress),
      AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(t.prRaised(formatInr(g['raised'], whole: true), formatInr(g['goal'], whole: true), (g['donations'] as num? ?? 0).toInt())),
          Text(t.prSpent(formatInr(g['spent'], whole: true), (g['approvedExpenses'] as num? ?? 0).toInt())),
          if (budget.isNotEmpty) ...[
            const Divider(),
            Row(children: [
              Expanded(child: Text(t.prBudgetHead, style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
              SizedBox(width: 84, child: Text(t.prBudget, textAlign: TextAlign.right, style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
              SizedBox(width: 84, child: Text(t.prSpentShort, textAlign: TextAlign.right, style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
            ]),
            for (final b in budget)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(children: [
                  Expanded(child: Text('${b['head']}')),
                  SizedBox(width: 84, child: Text(formatInr(b['planned'], whole: true), textAlign: TextAlign.right)),
                  SizedBox(width: 84, child: Text(formatInr(b['spent'], whole: true), textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w600))),
                ]),
              ),
            if (amountOf(g['spent']) > budget.fold<num>(0, (a, b) => a + amountOf(b['spent'])))
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(children: [
                  Expanded(child: Text(t.prOtherExpenses, style: const TextStyle(color: AppColors.textMuted))),
                  const SizedBox(width: 84, child: Text('—', textAlign: TextAlign.right)),
                  SizedBox(
                      width: 84,
                      child: Text(formatInr(amountOf(g['spent']) - budget.fold<num>(0, (a, b) => a + amountOf(b['spent'])), whole: true),
                          textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w600))),
                ]),
              ),
          ],
        ]),
      ),
    ]);
  }
}

class _Log extends StatelessWidget {
  const _Log({required this.p});
  final Map<String, dynamic> p;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final events = csrMaps(p['events']).reversed.toList();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SectionHeader(t.prLog),
      AppCard(
        child: Column(children: [
          for (final e in events)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: Icon(switch (e['kind']) {
                'NOTE' => Icons.chat_bubble_outline,
                'SENT' => Icons.outgoing_mail,
                'FUNDED' => Icons.verified,
                'DECLINED' => Icons.cancel_outlined,
                _ => Icons.history,
              }, color: AppColors.primary),
              title: Text(e['note'] == null ? proposalEventLabel(t, e['kind']) : '${e['note']}'),
              subtitle: Text([
                formatDateTime(e['at']),
                if (e['note'] != null) proposalEventLabel(t, e['kind']),
                if (e['followUpOn'] != null) t.prFollowUpOn(formatDate(e['followUpOn'])),
              ].join(' · ')),
            ),
        ]),
      ),
    ]);
  }
}

class _Actions extends ConsumerWidget {
  const _Actions({required this.p, required this.ngoId});
  final Map<String, dynamic> p;
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final s = p['status'];
    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/csr-proposals/${p['id']}';
    Future<bool> run(Future<Map<String, dynamic>> Function() call) async {
      try {
        await call();
        invalidateProposals(ref, ngoId, '${p['id']}');
        return true;
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
        return false;
      }
    }

    Widget more() => PopupMenuButton<String>(
          icon: const Icon(Icons.more_vert),
          onSelected: (v) async {
            if (v == 'DECLINED' || v == 'WITHDRAWN') {
              final reason = await _askText(context, v == 'DECLINED' ? t.prDeclined : t.prWithdraw, t.prReason, min: 3);
              if (reason != null) await run(() => api.post('$base/close', body: {'outcome': v, 'reason': reason}));
            }
          },
          itemBuilder: (_) => [
            if (s == 'SENT') PopupMenuItem(value: 'DECLINED', child: Text(t.prDeclined)),
            PopupMenuItem(value: 'WITHDRAWN', child: Text(t.prWithdraw)),
          ],
        );

    final Widget? child = switch (s) {
      'DRAFT' => Row(children: [
          Expanded(
            child: BusyButton(
              label: t.prMarkSent,
              icon: Icons.outgoing_mail,
              onPressed: () async {
                final r = await showModalBottomSheet<(DateTime, DateTime?)>(context: context, isScrollControlled: true, builder: (_) => const _SendSheet());
                if (r == null) return;
                final ok = await run(() => api.post('$base/send',
                    body: {'sentOn': _day.format(r.$1), if (r.$2 != null) 'followUpOn': _day.format(r.$2!), 'version': p['version']}));
                if (ok && context.mounted) {
                  await downloadAndOffer(context, ref, '$base/pdf', '${'${p['number']}'.replaceAll('/', '-')}.pdf', 'application/pdf');
                }
              },
            ),
          ),
          more(),
        ]),
      'SENT' => Row(children: [
          Expanded(
            child: OutlinedButton.icon(
              icon: const Icon(Icons.add_comment_outlined),
              label: Text(t.prAddNote),
              onPressed: () async {
                final r = await showModalBottomSheet<(String, DateTime?)>(context: context, isScrollControlled: true, builder: (_) => const _NoteSheet());
                if (r != null) await run(() => api.post('$base/notes', body: {'note': r.$1, if (r.$2 != null) 'followUpOn': _day.format(r.$2!)}));
              },
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: FilledButton.icon(
              style: FilledButton.styleFrom(backgroundColor: AppColors.success),
              icon: const Icon(Icons.verified),
              label: Text(t.prFunded),
              onPressed: () async {
                final r = await showModalBottomSheet<Map<String, dynamic>>(
                    context: context, isScrollControlled: true, builder: (_) => _FundedSheet(ask: p['askAmount'], sentOn: p['sentOn']));
                if (r != null) await run(() => api.post('$base/close', body: {'outcome': 'FUNDED', ...r}));
              },
            ),
          ),
          more(),
        ]),
      'FUNDED' => FilledButton.icon(
          icon: const Icon(Icons.verified_outlined),
          label: Text(t.prPrepareUc),
          onPressed: () async {
            final id = await showModalBottomSheet<String>(
                context: context,
                isScrollControlled: true,
                builder: (_) => UcFormSheet(ngoId: ngoId, campaignId: '${p['campaignId']}', prefill: {
                      'funderName': p['funderName'],
                      'funderReference': p['funderReference'],
                      'grantAmount': p['fundedAmount'],
                      'grantReceivedOn': p['fundedOn'],
                      'proposalId': p['id'],
                    }));
            if (id != null && context.mounted) {
              invalidateUcs(ref, ngoId);
              invalidateProposals(ref, ngoId, '${p['id']}');
              context.push('/ngo/ucs/$id');
            }
          }),
      _ => null,
    };
    if (child == null) return const SizedBox.shrink();
    return SafeArea(child: Padding(padding: const EdgeInsets.all(AppSpacing.md), child: child));
  }
}

Future<String?> _askText(BuildContext context, String title, String label, {int min = 2}) {
  final t = L10n.of(context);
  final c = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (d) => AlertDialog(
      title: Text(title),
      content: TextField(controller: c, maxLength: 300, maxLines: 3, minLines: 1, decoration: InputDecoration(labelText: label)),
      actions: [
        TextButton(onPressed: () => Navigator.pop(d), child: Text(t.cancel)),
        FilledButton(onPressed: () { if (c.text.trim().length >= min) Navigator.pop(d, c.text.trim()); }, child: Text(t.save)),
      ],
    ),
  );
}

class _SendSheet extends StatefulWidget {
  const _SendSheet();
  @override
  State<_SendSheet> createState() => _SendSheetState();
}

class _SendSheetState extends State<_SendSheet> {
  DateTime _sent = DateUtils.dateOnly(DateTime.now());
  DateTime? _follow = DateUtils.dateOnly(DateTime.now()).add(const Duration(days: 14));

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final today = DateUtils.dateOnly(DateTime.now());
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(t.prMarkSent, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: AppSpacing.sm),
          Text(t.prSendHint, style: const TextStyle(color: AppColors.textMuted)),
          const SizedBox(height: AppSpacing.md),
          _dateField(context, t.prSentOn, _sent, () async {
            final d = await _pickDate(context, _sent, first: today.subtract(const Duration(days: 365)), last: today);
            if (d != null) setState(() => _sent = d);
          }),
          const SizedBox(height: AppSpacing.sm),
          _dateField(context, t.prFollowUp, _follow, () async {
            final d = await _pickDate(context, _follow, first: today, last: today.add(const Duration(days: 365)));
            if (d != null) setState(() => _follow = d);
          }),
          const SizedBox(height: AppSpacing.lg),
          FilledButton.icon(onPressed: () => Navigator.pop(context, (_sent, _follow)), icon: const Icon(Icons.lock_outline), label: Text(t.prSendConfirm)),
        ]),
      ),
    );
  }
}

class _NoteSheet extends StatefulWidget {
  const _NoteSheet();
  @override
  State<_NoteSheet> createState() => _NoteSheetState();
}

class _NoteSheetState extends State<_NoteSheet> {
  final _note = TextEditingController();
  DateTime? _follow;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final today = DateUtils.dateOnly(DateTime.now());
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(t.prAddNote, style: Theme.of(context).textTheme.titleLarge),
            TextField(controller: _note, maxLength: 1000, maxLines: 4, minLines: 2, decoration: InputDecoration(labelText: t.prNoteHint)),
            _dateField(context, t.prNextFollowUp, _follow, () async {
              final d = await _pickDate(context, _follow, first: today, last: today.add(const Duration(days: 365)));
              if (d != null) setState(() => _follow = d);
            }),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
                onPressed: () { if (_note.text.trim().length >= 2) Navigator.pop(context, (_note.text.trim(), _follow)); },
                child: Text(t.save)),
          ]),
        ),
      ),
    );
  }
}

class _FundedSheet extends StatefulWidget {
  const _FundedSheet({required this.ask, required this.sentOn});
  final Object? ask;
  final Object? sentOn;
  @override
  State<_FundedSheet> createState() => _FundedSheetState();
}

class _FundedSheetState extends State<_FundedSheet> {
  late final _amount = TextEditingController(text: '${amountOf(widget.ask).round()}');
  final _ref = TextEditingController();
  DateTime _on = DateUtils.dateOnly(DateTime.now());

  @override
  void dispose() {
    _amount.dispose();
    _ref.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final today = DateUtils.dateOnly(DateTime.now());
    final sent = DateTime.tryParse('${widget.sentOn}');
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(t.prFunded, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: AppSpacing.sm),
            TextField(controller: _amount, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: t.prFundedAmount, prefixText: '₹ ')),
            TextField(controller: _ref, maxLength: 120, decoration: InputDecoration(labelText: t.ucReference, helperText: t.ucReferenceHint)),
            _dateField(context, t.prFundedOn, _on, () async {
              final d = await _pickDate(context, _on, first: sent ?? today.subtract(const Duration(days: 365)), last: today);
              if (d != null) setState(() => _on = d);
            }),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: AppColors.success),
              onPressed: () {
                final a = _amount.text.replaceAll(',', '').trim();
                if ((num.tryParse(a) ?? 0) <= 0) return;
                Navigator.pop(context, {'amount': a, 'on': _day.format(_on), if (_ref.text.trim().isNotEmpty) 'reference': _ref.text.trim()});
              },
              child: Text(t.prRecordFunded),
            ),
          ]),
        ),
      ),
    );
  }
}

// ==================================================================================================== editor

class ProposalEditPage extends ConsumerStatefulWidget {
  const ProposalEditPage({super.key, required this.proposalId});
  final String proposalId;
  @override
  ConsumerState<ProposalEditPage> createState() => _ProposalEditPageState();
}

class _ProposalEditPageState extends ConsumerState<ProposalEditPage> {
  final _form = GlobalKey<FormState>();
  final Map<String, TextEditingController> _c = {
    for (final k in ['title', 'funderName', 'funderContact', 'funderEmail', 'askAmount', 'durationMonths', 'beneficiaries', 'location', 'need',
      'objectives', 'activities', 'outcomes', 'monitoring', 'contactName', 'contactDetails'])
      k: TextEditingController(),
  };
  Map<String, dynamic>? _p;
  String? _area;
  List<Map<String, dynamic>> _areas = const [];
  List<Map<String, dynamic>> _packs = const [];
  String? _packId;
  Map<String, dynamic>? _pack;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final ngoId = ref.read(currentNgoIdProvider)!;
    final api = ref.read(apiProvider);
    try {
      final p = await api.getJson('/api/v1/ngos/$ngoId/csr-proposals/${widget.proposalId}');
      final l = await api.getJson('/api/v1/ngos/$ngoId/csr-proposals', query: {'campaignId': '${p['campaignId']}'});
      List<Map<String, dynamic>> packs = const [];
      try {
        final o = await api.getJson('/api/v1/ngos/$ngoId/csr/overview');
        packs = csrMaps(o['packs']).where((x) => x['status'] == 'SHARED').toList();
      } catch (_) {}
      if (!mounted) return;
      setState(() {
        _p = p;
        _areas = csrMaps(l['scheduleVii']);
        _area = p['scheduleVii'] as String?;
        _packs = packs;
        _packId = p['packId'] as String?;
        if (_packId != null) _pack = {'id': _packId, 'title': p['packTitle'], 'link': p['packUrl'], 'expiresAt': p['packExpiresAt']};
        for (final e in _c.entries) {
          final v = p[e.key];
          e.value.text = v == null ? '' : (e.key == 'askAmount' ? '${amountOf(v).round()}' : '$v');
        }
      });
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  @override
  void dispose() {
    for (final c in _c.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _choosePack(String? id) async {
    setState(() => _packId = id);
    if (id == null) {
      setState(() => _pack = null);
      return;
    }
    try {
      final d = await ref.read(apiProvider).getJson('/api/v1/ngos/${ref.read(currentNgoIdProvider)}/csr/packs/$id');
      if (mounted) setState(() => _pack = {'id': id, 'title': d['title'], 'link': d['link'], 'expiresAt': d['expiresAt']});
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  Widget _field(String key, String label, {String? hint, int lines = 1, TextInputType? type, int? max, String? Function(String?)? validator}) =>
      Padding(
        padding: const EdgeInsets.only(bottom: AppSpacing.sm),
        child: TextFormField(
          controller: _c[key],
          minLines: lines,
          maxLines: lines == 1 ? 1 : lines + 6,
          maxLength: max,
          keyboardType: type ?? (lines > 1 ? TextInputType.multiline : null),
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(labelText: label, helperText: hint, helperMaxLines: 3, alignLabelWithHint: lines > 1),
          validator: validator,
        ),
      );

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final p = _p;
    return Scaffold(
      appBar: AppBar(title: Text(p == null ? t.prEdit : '${p['number']}')),
      body: p == null
          ? const LoadingView()
          : Form(
              key: _form,
              child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
                SectionHeader(t.prBasics),
                _field('title', t.prProjectTitle, max: 160, validator: (v) => (v ?? '').trim().length < 5 ? t.prTitleRequired : null),
                _field('funderName', t.prFunder, max: 160, validator: (v) => (v ?? '').trim().length < 2 ? t.ucFunderRequired : null),
                _field('funderContact', t.prFunderContact, max: 120),
                _field('funderEmail', t.prFunderEmail, type: TextInputType.emailAddress),
                DropdownButtonFormField<String>(
                  initialValue: _area,
                  isExpanded: true,
                  decoration: InputDecoration(labelText: t.prScheduleVii, helperText: t.prScheduleViiHint, helperMaxLines: 2),
                  items: [for (final a in _areas) DropdownMenuItem(value: '${a['code']}', child: Text('${a['label']}', maxLines: 2, overflow: TextOverflow.ellipsis))],
                  onChanged: (v) => setState(() => _area = v),
                ),
                const SizedBox(height: AppSpacing.sm),
                Row(children: [
                  Expanded(child: _field('askAmount', t.prAsk, type: TextInputType.number,
                      validator: (v) => (num.tryParse((v ?? '').replaceAll(',', '')) ?? 0) <= 0 ? t.ucGrantRequired : null)),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: _field('durationMonths', t.prMonths, type: TextInputType.number,
                      validator: (v) { final n = int.tryParse(v ?? ''); return n == null || n < 1 || n > 60 ? t.prMonthsRange : null; })),
                ]),
                Row(children: [
                  Expanded(child: _field('beneficiaries', t.prBeneficiaries, type: TextInputType.number)),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: _field('location', t.prLocation, max: 160)),
                ]),
                SectionHeader(t.prTheProposal),
                _field('need', t.prNeed, hint: t.prNeedHint, lines: 4),
                _field('objectives', t.prObjectives, hint: t.prObjectivesHint, lines: 3),
                _field('activities', t.prActivities, hint: t.prActivitiesHint, lines: 3),
                _field('outcomes', t.prOutcomes, hint: t.prOutcomesHint, lines: 3),
                _field('monitoring', t.prMonitoring, hint: t.prMonitoringHint, lines: 2),
                SectionHeader(t.prComplianceDocs),
                DropdownButtonFormField<String?>(
                  initialValue: _packs.any((x) => x['id'] == _packId) ? _packId : null,
                  isExpanded: true,
                  decoration: InputDecoration(labelText: t.prPack, helperText: _packs.isEmpty ? t.prNoSharedPack : t.prPackHint, helperMaxLines: 2),
                  items: [
                    DropdownMenuItem<String?>(value: null, child: Text(t.prNoPackChoice)),
                    for (final x in _packs) DropdownMenuItem<String?>(value: '${x['id']}', child: Text('${x['title']} · ${x['recipientName'] ?? ''}', overflow: TextOverflow.ellipsis)),
                  ],
                  onChanged: _choosePack,
                ),
                SectionHeader(t.prContact),
                _field('contactName', t.prContactName, max: 120),
                _field('contactDetails', t.prContactDetails, max: 200),
                const SizedBox(height: AppSpacing.md),
                BusyButton(
                  label: t.save,
                  icon: Icons.save_outlined,
                  onPressed: () async {
                    if (!_form.currentState!.validate()) return;
                    String? v(String k) => _c[k]!.text.trim().isEmpty ? null : _c[k]!.text.trim();
                    try {
                      await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/csr-proposals/${widget.proposalId}', body: {
                        'title': v('title'),
                        'funderName': v('funderName'),
                        'funderContact': v('funderContact'),
                        'funderEmail': v('funderEmail'),
                        'scheduleVii': _area,
                        'askAmount': v('askAmount')?.replaceAll(',', ''),
                        'durationMonths': int.tryParse(v('durationMonths') ?? ''),
                        'beneficiaries': int.tryParse(v('beneficiaries') ?? ''),
                        'location': v('location'),
                        'need': _c['need']!.text,
                        'objectives': _c['objectives']!.text,
                        'activities': _c['activities']!.text,
                        'outcomes': _c['outcomes']!.text,
                        'monitoring': _c['monitoring']!.text,
                        'contactName': v('contactName'),
                        'contactDetails': v('contactDetails'),
                        'packId': _pack?['id'],
                        'packTitle': _pack?['title'],
                        'packUrl': _pack?['link'],
                        'packExpiresAt': _pack?['expiresAt'],
                        'version': p['version'],
                      });
                      if (!context.mounted) return;
                      invalidateProposals(ref, ngoId!, widget.proposalId);
                      context.pop();
                    } catch (e) {
                      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                    }
                  },
                ),
                const SizedBox(height: AppSpacing.xl),
              ]),
            ),
    );
  }
}
