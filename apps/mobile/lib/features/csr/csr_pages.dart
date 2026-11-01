import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../documents/documents_api.dart';
import 'csr_api.dart';

/// CSR packs of the NGO (administrators). Enterprise feature; older packs stay visible after a downgrade.
class CsrPage extends ConsumerWidget {
  const CsrPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(csrOverviewProvider(ngoId));
    final enabled = data.value?['enabled'] == true;
    return Scaffold(
      appBar: AppBar(title: Text(t.csrTitle)),
      floatingActionButton: enabled
          ? FloatingActionButton.extended(
              onPressed: () => _newPack(context, ref, ngoId), icon: const Icon(Icons.add), label: Text(t.csrNewPack))
          : null,
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(csrOverviewProvider(ngoId))),
        data: (o) {
          final packs = csrMaps(o['packs']);
          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(csrOverviewProvider(ngoId)),
            child: ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 96), children: [
              AppCard(
                color: AppColors.primarySoft,
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    const Icon(Icons.handshake_outlined, color: AppColors.primary),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(child: Text(t.csrIntroTitle, style: Theme.of(context).textTheme.titleMedium)),
                    const StatusBadge('ENTERPRISE', tone: BadgeTone.info),
                  ]),
                  const SizedBox(height: AppSpacing.sm),
                  Text(t.csrIntro),
                ]),
              ),
              if (!enabled) ...[
                const SizedBox(height: AppSpacing.md),
                AppCard(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(t.csrEnterpriseOnly, style: Theme.of(context).textTheme.titleSmall),
                    const SizedBox(height: AppSpacing.sm),
                    Text(t.csrEnterpriseHint, style: const TextStyle(color: AppColors.textMuted)),
                    const SizedBox(height: AppSpacing.md),
                    OutlinedButton.icon(
                        onPressed: () => context.push('/ngo/subscription'),
                        icon: const Icon(Icons.workspace_premium),
                        label: Text(t.csrSeePlans)),
                  ]),
                ),
              ],
              SectionHeader(t.csrPacks),
              if (packs.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                  child: Text(t.csrNoPacks, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted)),
                ),
              for (final p in packs) _PackTile(p: p, onTap: () => context.push('/ngo/csr/${p['id']}')),
            ]),
          );
        },
      ),
    );
  }

  Future<void> _newPack(BuildContext context, WidgetRef ref, String ngoId) async {
    final id = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (_) => PackFormSheet(ngoId: ngoId),
    );
    if (id != null && context.mounted) {
      invalidateCsr(ref, ngoId);
      context.push('/ngo/csr/$id');
    }
  }
}

class _PackTile extends StatelessWidget {
  const _PackTile({required this.p, required this.onTap});
  final Map<String, dynamic> p;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final status = '${p['status']}';
    final lines = <String>[
      t.csrDocCount((p['documents'] as num?)?.toInt() ?? 0),
      if (status == 'SHARED' && p['expiresAt'] != null) t.csrOpenUntil(formatDate(p['expiresAt'])),
      if (status != 'DRAFT') t.csrOpenedTimes((p['opened'] as num?)?.toInt() ?? 0),
    ];
    return AppCard(
      onTap: onTap,
      child: Row(children: [
        const CircleAvatar(backgroundColor: AppColors.primarySoft, child: Icon(Icons.folder_shared_outlined, color: AppColors.primary)),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${p['recipientName']}', style: Theme.of(context).textTheme.titleSmall, maxLines: 1, overflow: TextOverflow.ellipsis),
            Text('${p['title']}', maxLines: 1, overflow: TextOverflow.ellipsis),
            Text(lines.join(' · '), style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
          ]),
        ),
        const SizedBox(width: AppSpacing.sm),
        StatusBadge(csrStatusLabel(t, status), tone: csrStatusTone(status)),
      ]),
    );
  }
}

/// New pack, or the details of a draft (company, note, how long the link stays open).
class PackFormSheet extends ConsumerStatefulWidget {
  const PackFormSheet({super.key, required this.ngoId, this.pack});
  final String ngoId;
  final Map<String, dynamic>? pack;

  @override
  ConsumerState<PackFormSheet> createState() => _PackFormSheetState();
}

class _PackFormSheetState extends ConsumerState<PackFormSheet> {
  final _form = GlobalKey<FormState>();
  late final _title = TextEditingController(text: widget.pack?['title'] as String?);
  late final _company = TextEditingController(text: widget.pack?['recipientName'] as String?);
  late final _email = TextEditingController(text: widget.pack?['recipientEmail'] as String?);
  late final _note = TextEditingController(text: widget.pack?['message'] as String?);
  late int _days = (widget.pack?['validDays'] as num?)?.toInt() ?? 30;

  @override
  void initState() {
    super.initState();
    if (widget.pack == null) {
      final d = DateTime.now();
      final s = d.month >= 4 ? d.year : d.year - 1;
      _title.text = L10nFallback.packTitle(s);
    }
  }

  @override
  void dispose() {
    for (final c in [_title, _company, _email, _note]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final editing = widget.pack != null;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Form(
          key: _form,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
            Text(editing ? t.csrEditPack : t.csrNewPack, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: AppSpacing.sm),
            if (!editing) Text(t.csrNewPackHint, style: const TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: AppSpacing.md),
            TextFormField(
              controller: _company,
              textCapitalization: TextCapitalization.words,
              maxLength: 160,
              decoration: InputDecoration(labelText: t.csrCompany, hintText: t.csrCompanyHint),
              validator: (v) => (v ?? '').trim().length < 2 ? t.csrCompanyRequired : null,
            ),
            TextFormField(
              controller: _email,
              keyboardType: TextInputType.emailAddress,
              decoration: InputDecoration(labelText: t.csrCompanyEmail, helperText: t.csrOptional),
            ),
            const SizedBox(height: AppSpacing.md),
            TextFormField(
              controller: _title,
              maxLength: 120,
              decoration: InputDecoration(labelText: t.csrPackName),
              validator: (v) => (v ?? '').trim().length < 3 ? t.csrPackNameRequired : null,
            ),
            TextFormField(
              controller: _note,
              maxLines: 3,
              maxLength: 1000,
              decoration: InputDecoration(labelText: t.csrNote, helperText: t.csrNoteHint),
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(t.csrLinkOpenFor, style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: AppSpacing.xs),
            Wrap(spacing: AppSpacing.sm, children: [
              for (final d in const [7, 15, 30, 60, 90])
                ChoiceChip(label: Text(t.csrDays(d)), selected: _days == d, onSelected: (_) => setState(() => _days = d)),
            ]),
            const SizedBox(height: AppSpacing.lg),
            BusyButton(
              label: editing ? t.save : t.csrCreateFromVault,
              icon: editing ? Icons.save_outlined : Icons.auto_awesome,
              onPressed: () async {
                if (!_form.currentState!.validate()) return;
                final body = {
                  'title': _title.text.trim(),
                  'recipientName': _company.text.trim(),
                  'recipientEmail': _email.text.trim(),
                  'message': _note.text.trim(),
                  'validDays': _days,
                  if (editing) 'version': widget.pack!['version'],
                };
                try {
                  final api = ref.read(apiProvider);
                  final r = editing
                      ? await api.put('/api/v1/ngos/${widget.ngoId}/csr/packs/${widget.pack!['id']}', body: body)
                      : await api.post('/api/v1/ngos/${widget.ngoId}/csr/packs', body: body);
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

/// Default pack name in English: the company reads it ("Compliance documents FY 2026-27").
abstract final class L10nFallback {
  static String packTitle(int fyStart) => 'Compliance documents FY $fyStart-${((fyStart + 1) % 100).toString().padLeft(2, '0')}';
}

/// One pack: readiness and documents while a draft; link, visits and "stop" once shared.
class CsrPackPage extends ConsumerWidget {
  const CsrPackPage({super.key, required this.packId});
  final String packId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(csrPackProvider((ngoId, packId)));
    final p = data.value;
    final draft = p?['status'] == 'DRAFT';
    return Scaffold(
      appBar: AppBar(title: Text(t.csrPack), actions: [
        if (draft)
          IconButton(
            tooltip: t.csrEditPack,
            icon: const Icon(Icons.edit_outlined),
            onPressed: () async {
              final r = await showModalBottomSheet<String>(
                  context: context, isScrollControlled: true, builder: (_) => PackFormSheet(ngoId: ngoId, pack: p));
              if (r != null) invalidateCsr(ref, ngoId, packId);
            },
          ),
      ]),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => invalidateCsr(ref, ngoId, packId)),
        data: (p) => RefreshIndicator(
          onRefresh: () async => invalidateCsr(ref, ngoId, packId),
          child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            _Header(p: p),
            if (p['status'] == 'DRAFT') _Readiness(p: p) else _LinkCard(p: p, ngoId: ngoId),
            SectionHeader(t.csrDocuments,
                action: p['status'] == 'DRAFT'
                    ? TextButton.icon(
                        onPressed: () => _addDocument(context, ref, ngoId, p), icon: const Icon(Icons.add), label: Text(t.csrAddDocument))
                    : null),
            for (final i in csrMaps(p['items'])) _ItemTile(i: i, ngoId: ngoId, pack: p),
            if (csrMaps(p['items']).isEmpty)
              Text(t.csrNoDocuments, style: const TextStyle(color: AppColors.textMuted)),
            if (p['status'] != 'DRAFT') _Visits(ngoId: ngoId, packId: packId),
            const SizedBox(height: AppSpacing.xl),
          ]),
        ),
      ),
      bottomNavigationBar: p == null ? null : _Actions(p: p, ngoId: ngoId),
    );
  }

  Future<void> _addDocument(BuildContext context, WidgetRef ref, String ngoId, Map<String, dynamic> p) async {
    final added = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (_) => VaultPickerSheet(ngoId: ngoId, pack: p),
    );
    if (added == true) invalidateCsr(ref, ngoId, '${p['id']}');
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.p});
  final Map<String, dynamic> p;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final status = '${p['status']}';
    return AppCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('${p['recipientName']}', style: Theme.of(context).textTheme.titleLarge)),
          StatusBadge(csrStatusLabel(t, status), tone: csrStatusTone(status)),
        ]),
        const SizedBox(height: AppSpacing.xs),
        Text('${p['title']}'),
        if ((p['recipientEmail'] ?? '').toString().isNotEmpty)
          Text('${p['recipientEmail']}', style: const TextStyle(color: AppColors.textMuted)),
        if ((p['message'] ?? '').toString().isNotEmpty) ...[
          const SizedBox(height: AppSpacing.sm),
          Text('${p['message']}', style: const TextStyle(fontStyle: FontStyle.italic)),
        ],
        const SizedBox(height: AppSpacing.sm),
        Text(
          switch (status) {
            'DRAFT' => t.csrLinkWillBeOpen(t.csrDays((p['validDays'] as num?)?.toInt() ?? 30)),
            'SHARED' => t.csrOpenUntil(formatDate(p['expiresAt'])),
            'EXPIRED' => t.csrExpiredOn(formatDate(p['expiresAt'])),
            _ => t.csrStoppedOn(formatDate(p['revokedAt'])),
          },
          style: const TextStyle(color: AppColors.textMuted, fontSize: 13),
        ),
      ]),
    );
  }
}

class _Readiness extends StatelessWidget {
  const _Readiness({required this.p});
  final Map<String, dynamic> p;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final r = Map<String, dynamic>.from(p['readiness'] as Map? ?? const {});
    final errors = (r['errors'] as List? ?? const []).map((e) => '$e').toList();
    final warnings = (r['warnings'] as List? ?? const []).map((e) => '$e').toList();
    final checklist = csrMaps(r['checklist']);
    final ready = errors.isEmpty && warnings.isEmpty;
    final color = errors.isNotEmpty ? AppColors.error : warnings.isNotEmpty ? const Color(0xFFB45309) : AppColors.success;
    return AppCard(
      color: color.withValues(alpha: 0.07),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(ready ? Icons.verified_outlined : errors.isNotEmpty ? Icons.error_outline : Icons.info_outline, color: color),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
              child: Text(ready ? t.csrReady : errors.isNotEmpty ? t.csrNotReady : t.csrAlmostReady,
                  style: TextStyle(fontWeight: FontWeight.w700, color: color))),
        ]),
        const SizedBox(height: AppSpacing.sm),
        Wrap(spacing: 6, runSpacing: 6, children: [
          for (final c in checklist)
            Chip(
              visualDensity: VisualDensity.compact,
              avatar: Icon((c['count'] as num? ?? 0) > 0 ? Icons.check_circle : Icons.radio_button_unchecked,
                  size: 18, color: (c['count'] as num? ?? 0) > 0 ? AppColors.success : AppColors.textMuted),
              label: Text(csrKindLabel(t, c['kind']), style: const TextStyle(fontSize: 12)),
            ),
        ]),
        for (final e in errors) _Issue(text: csrIssueLabel(t, e), color: AppColors.error, icon: Icons.block),
        for (final w in warnings) _Issue(text: csrIssueLabel(t, w), color: const Color(0xFFB45309), icon: Icons.warning_amber_rounded),
      ]),
    );
  }
}

class _Issue extends StatelessWidget {
  const _Issue({required this.text, required this.color, required this.icon});
  final String text;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: AppSpacing.xs),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 6),
          Expanded(child: Text(text, style: TextStyle(color: color, fontSize: 13))),
        ]),
      );
}

class _LinkCard extends StatelessWidget {
  const _LinkCard({required this.p, required this.ngoId});
  final Map<String, dynamic> p;
  final String ngoId;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final live = p['status'] == 'SHARED';
    final last = p['lastAt'];
    return AppCard(
      color: live ? AppColors.primarySoft : null,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(live ? t.csrLinkForCompany : t.csrLinkClosed, style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: AppSpacing.xs),
        SelectableText('${p['link']}', style: TextStyle(fontSize: 12, color: live ? AppColors.primary : AppColors.textMuted)),
        const SizedBox(height: AppSpacing.sm),
        Row(children: [
          Expanded(child: StatTileMini(label: t.csrOpened, value: '${p['opened'] ?? 0}')),
          Expanded(child: StatTileMini(label: t.csrDownloads, value: '${p['downloads'] ?? 0}')),
        ]),
        if (last != null) Text(t.csrLastVisit(formatDate(last)), style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        if (live) ...[
          const SizedBox(height: AppSpacing.sm),
          Row(children: [
            Expanded(
                child: FilledButton.icon(
                    onPressed: () => shareCsrLink(context, p), icon: const Icon(Icons.share_outlined), label: Text(t.csrShareLink))),
            const SizedBox(width: AppSpacing.sm),
            // The theme gives buttons full width (Size.fromHeight): inside a Row each needs its share of the space.
            Expanded(
                child: OutlinedButton.icon(onPressed: () => copyCsrLink(context, p), icon: const Icon(Icons.copy), label: Text(t.csrCopy))),
          ]),
        ],
      ]),
    );
  }
}

class StatTileMini extends StatelessWidget {
  const StatTileMini({super.key, required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(value, style: Theme.of(context).textTheme.titleLarge),
          Text(label, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        ]),
      );
}

class _ItemTile extends ConsumerWidget {
  const _ItemTile({required this.i, required this.ngoId, required this.pack});
  final Map<String, dynamic> i;
  final String ngoId;
  final Map<String, dynamic> pack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final draft = pack['status'] == 'DRAFT';
    final removed = i['documentStatus'] != 'ACTIVE';
    final exp = i['expiresOn'] == null ? null : DateTime.tryParse('${i['expiresOn']}');
    final today = DateUtils.dateOnly(DateTime.now());
    final expired = exp != null && exp.isBefore(today);
    final soon = exp != null && !expired && exp.difference(today).inDays <= 30;
    final meta = [
      if (i['fileType'] != null) fileTypeLabel(t, i['fileType'] as String?),
      if (i['sizeBytes'] != null) sizeLabel(i['sizeBytes']),
      if (exp != null) t.csrValidTill(formatDate(i['expiresOn'])),
    ].join(' · ');
    return Card(
      child: ListTile(
        leading: Icon(Icons.description_outlined, color: csrKindColor(i['kind'])),
        title: Text('${i['title']}', maxLines: 2, overflow: TextOverflow.ellipsis),
        subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(csrKindLabel(t, i['kind']), style: const TextStyle(color: AppColors.primary, fontWeight: FontWeight.w600, fontSize: 12)),
          if (meta.isNotEmpty) Text(meta, style: const TextStyle(fontSize: 12)),
          if (removed) Text(t.csrDocRemoved, style: const TextStyle(color: AppColors.error, fontSize: 12))
          else if (expired) Text(t.csrDocExpired, style: const TextStyle(color: AppColors.error, fontSize: 12))
          else if (soon) Text(t.csrDocExpiresSoon, style: const TextStyle(color: Color(0xFFB45309), fontSize: 12)),
          if (i['sha256'] != null)
            Text(t.csrFingerprint('${i['sha256']}'.substring(0, 12)), style: const TextStyle(fontSize: 11, color: AppColors.textMuted)),
        ]),
        isThreeLine: true,
        onTap: removed ? null : () => _open(context, ref),
        trailing: draft
            ? IconButton(
                tooltip: t.csrRemove,
                icon: const Icon(Icons.remove_circle_outline),
                onPressed: () async {
                  try {
                    await ref.read(apiProvider).delete('/api/v1/ngos/$ngoId/csr/packs/${pack['id']}/items/${i['id']}');
                    invalidateCsr(ref, ngoId, '${pack['id']}');
                  } catch (e) {
                    if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                  }
                },
              )
            : null,
      ),
    );
  }

  Future<void> _open(BuildContext context, WidgetRef ref) async {
    try {
      final d = await ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/library/${i['documentId']}');
      if (context.mounted) await openDocument(context, ref, ngoId, d);
    } catch (e) {
      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

class _Visits extends ConsumerWidget {
  const _Visits({required this.ngoId, required this.packId});
  final String ngoId;
  final String packId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final v = ref.watch(csrViewsProvider((ngoId, packId))).value ?? const [];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SectionHeader(t.csrVisits),
      if (v.isEmpty) Text(t.csrNoVisits, style: const TextStyle(color: AppColors.textMuted)),
      for (final x in v.take(30))
        ListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          leading: Icon(switch (x['action']) {
            'OPENED' => Icons.visibility_outlined,
            'DOWNLOADED_ALL' => Icons.folder_zip_outlined,
            _ => Icons.download_outlined,
          }, color: AppColors.primary),
          title: Text(switch (x['action']) {
            'OPENED' => t.csrVisitOpened,
            'DOWNLOADED_ALL' => t.csrVisitZip,
            _ => t.csrVisitDownloaded('${x['itemTitle'] ?? ''}'),
          }),
          subtitle: Text(formatDateTime(x['at'])),
        ),
    ]);
  }
}

String formatDateTime(Object? iso) {
  final d = iso == null ? null : DateTime.tryParse('$iso');
  if (d == null) return '';
  final l = d.toLocal();
  final h = l.hour % 12 == 0 ? 12 : l.hour % 12;
  return '${formatDate(iso)}, $h:${l.minute.toString().padLeft(2, '0')} ${l.hour < 12 ? 'AM' : 'PM'}';
}

class _Actions extends ConsumerWidget {
  const _Actions({required this.p, required this.ngoId});
  final Map<String, dynamic> p;
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final status = '${p['status']}';
    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/csr/packs/${p['id']}';
    Future<void> run(Future<Map<String, dynamic>> Function() call, {bool open = false}) async {
      try {
        final r = await call();
        invalidateCsr(ref, ngoId, '${p['id']}');
        if (open && context.mounted) context.pushReplacement('/ngo/csr/${r['id']}');
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
      }
    }

    final Widget child = switch (status) {
      'DRAFT' => BusyButton(
          label: t.csrShareWithCompany,
          icon: Icons.send_outlined,
          onPressed: () async {
            final r = Map<String, dynamic>.from(p['readiness'] as Map? ?? const {});
            final errors = (r['errors'] as List? ?? const []).map((e) => '$e').toList();
            final warnings = (r['warnings'] as List? ?? const []).map((e) => '$e').toList();
            if (errors.isNotEmpty) {
              showSnack(context, t.csrFixFirst, error: true);
              return;
            }
            final ok = await confirm(context,
                title: t.csrShareConfirmTitle,
                message: [
                  t.csrShareConfirm('${p['recipientName']}', t.csrDays((p['validDays'] as num?)?.toInt() ?? 30)),
                  if (warnings.isNotEmpty) '\n${t.csrShareWarnings}',
                  for (final w in warnings) '• ${csrIssueLabel(t, w)}',
                ].join('\n'),
                confirmLabel: t.csrShare,
                cancelLabel: t.cancel);
            if (!ok) return;
            await run(() => api.post('$base/share', body: {'version': p['version'], 'acceptWarnings': true}));
          }),
      'SHARED' => OutlinedButton.icon(
          style: OutlinedButton.styleFrom(foregroundColor: AppColors.error),
          icon: const Icon(Icons.link_off),
          label: Text(t.csrStopLink),
          onPressed: () async {
            final ok = await confirm(context,
                title: t.csrStopLink, message: t.csrStopConfirm, confirmLabel: t.csrStopLink, cancelLabel: t.cancel, destructive: true);
            if (ok) await run(() => api.post('$base/revoke'));
          }),
      _ => BusyButton(label: t.csrMakeCopy, icon: Icons.copy_all_outlined, onPressed: () => run(() => api.post('$base/copy'), open: true)),
    };
    return SafeArea(child: Padding(padding: const EdgeInsets.all(AppSpacing.md), child: child));
  }
}

/// Picks a vault document for the pack: first choose what it proves, then tap a suggestion or search the vault.
class VaultPickerSheet extends ConsumerStatefulWidget {
  const VaultPickerSheet({super.key, required this.ngoId, required this.pack});
  final String ngoId;
  final Map<String, dynamic> pack;

  @override
  ConsumerState<VaultPickerSheet> createState() => _VaultPickerSheetState();
}

class _VaultPickerSheetState extends ConsumerState<VaultPickerSheet> {
  String _kind = 'CSR1';
  String _q = '';
  List<Map<String, dynamic>> _results = const [];
  bool _searching = false;

  @override
  void initState() {
    super.initState();
    final have = csrMaps(widget.pack['items']).map((i) => i['kind']).toSet();
    _kind = csrKinds.firstWhere((k) => csrRequiredKinds.contains(k) && !have.contains(k), orElse: () => 'OTHER');
  }

  Future<void> _search(String q) async {
    setState(() { _q = q; _searching = true; });
    try {
      final r = await ref.read(apiProvider).getJson('/api/v1/ngos/${widget.ngoId}/library', query: {'q': q, 'size': '20'});
      if (mounted && _q == q) setState(() => _results = csrMaps(r['items']));
    } catch (_) {
      if (mounted) setState(() => _results = const []);
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  Future<void> _add(String documentId) async {
    try {
      await ref.read(apiProvider).post('/api/v1/ngos/${widget.ngoId}/csr/packs/${widget.pack['id']}/items',
          body: {'documentId': documentId, 'kind': _kind});
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final inPack = csrMaps(widget.pack['items']).map((i) => '${i['documentId']}').toSet();
    final sug = ref.watch(csrSuggestionsProvider(widget.ngoId)).value ?? const {};
    final suggestions = csrMaps(sug[_kind]).where((d) => !inPack.contains('${d['documentId']}')).toList();
    final found = _results.where((d) => !inPack.contains('${d['id']}') && d['status'] == 'ACTIVE').toList();
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.85,
      maxChildSize: 0.95,
      builder: (context, scroll) => ListView(controller: scroll, padding: const EdgeInsets.all(AppSpacing.lg), children: [
        Text(t.csrAddDocument, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: AppSpacing.md),
        DropdownButtonFormField<String>(
          initialValue: _kind,
          decoration: InputDecoration(labelText: t.csrWhatIsIt),
          items: [for (final k in csrKinds) DropdownMenuItem(value: k, child: Text(csrKindLabel(t, k)))],
          onChanged: (v) => setState(() => _kind = v ?? 'OTHER'),
        ),
        SectionHeader(t.csrSuggested),
        if (suggestions.isEmpty) Text(t.csrNoSuggestion, style: const TextStyle(color: AppColors.textMuted)),
        for (final d in suggestions)
          Card(
            child: ListTile(
              leading: const Icon(Icons.auto_awesome, color: AppColors.primary),
              title: Text('${d['title']}'),
              subtitle: Text([
                fileTypeLabel(t, d['fileType'] as String?),
                if (d['expiresOn'] != null) t.csrValidTill(formatDate(d['expiresOn'])),
                if (d['expired'] == true) t.csrDocExpired,
              ].join(' · ')),
              trailing: const Icon(Icons.add_circle_outline),
              onTap: () => _add('${d['documentId']}'),
            ),
          ),
        SectionHeader(t.csrSearchVault),
        TextField(
          decoration: InputDecoration(prefixIcon: const Icon(Icons.search), hintText: t.csrSearchHint),
          onChanged: (v) => _search(v.trim()),
        ),
        if (_searching) const Padding(padding: EdgeInsets.all(AppSpacing.md), child: LinearProgressIndicator()),
        for (final d in found)
          ListTile(
            leading: Icon(categoryIcon(d['category'] as String?), color: categoryColor(d['category'] as String?)),
            title: Text('${d['title']}'),
            subtitle: Text('${categoryLabel(t, d['category'] as String?)} · ${fileTypeLabel(t, d['fileType'] as String?)}'),
            trailing: const Icon(Icons.add_circle_outline),
            onTap: () => _add('${d['id']}'),
          ),
        if (_q.isNotEmpty && !_searching && found.isEmpty)
          Padding(padding: const EdgeInsets.all(AppSpacing.md), child: Text(t.csrNothingFound, style: const TextStyle(color: AppColors.textMuted))),
        const SizedBox(height: AppSpacing.md),
        Text(t.csrUploadHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
      ]),
    );
  }
}
