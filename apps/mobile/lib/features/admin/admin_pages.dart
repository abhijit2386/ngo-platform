import 'dart:io';

import 'package:flutter/material.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';

import '../../core/auth/auth_service.dart';
import '../../core/session/preferences.dart';
import '../../core/session/session.dart';
import '../../core/util/labels.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';

final _queueProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson('/api/v1/admin/verifications', query: {'status': 'PENDING'});
  return (r['items'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final _adminNgoProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, id) async {
  ref.watch(sessionKeyProvider);
  final api = ref.read(apiProvider);
  final results = await Future.wait([
    api.getJson('/api/v1/admin/ngos/$id'),
    api.getList('/api/v1/ngos/$id/documents'),
  ]);
  return {...results[0] as Map<String, dynamic>, 'documents': results[1]};
});

/// ADMIN-002: verification queue (oldest first).
class VerificationQueuePage extends ConsumerWidget {
  const VerificationQueuePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final q = ref.watch(_queueProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t.verificationQueue), actions: [
        IconButton(tooltip: t.settings, icon: const Icon(Icons.settings_outlined), onPressed: () => context.push('/settings')),
        IconButton(
          tooltip: t.signOut,
          icon: const Icon(Icons.logout),
          onPressed: () async {
            await ref.read(prefsProvider.notifier).resetForSignOut();
            await ref.read(authServiceProvider).signOut();
          },
        ),
      ]),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(_queueProvider),
        child: q.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_queueProvider)),
          data: (list) => list.isEmpty
              ? ListView(children: [EmptyState(icon: Icons.task_alt, title: t.nothingToReview)])
              : ListView.separated(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  itemCount: list.length,
                  separatorBuilder: (_, __) => const SizedBox(height: AppSpacing.sm),
                  itemBuilder: (_, i) {
                    final n = list[i];
                    final flags = List<String>.from(n['riskFlags'] as List? ?? const []);
                    return AppCard(
                      onTap: () => context.push('/admin/ngos/${n['id']}'),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${n['displayName']}', style: Theme.of(context).textTheme.titleMedium),
                        Text('${n['legalName']} · ${n['registrationState']}', style: const TextStyle(color: AppColors.textMuted)),
                        Text(formatDate(n['submittedAt']), style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                        if (flags.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          Wrap(spacing: 6, runSpacing: 4, children: [for (final f in flags) StatusBadge(f, tone: BadgeTone.warning)]),
                        ],
                      ]),
                    );
                  },
                ),
        ),
      ),
    );
  }
}

/// ADMIN-003/004: review details & decide.
class VerificationDetailPage extends ConsumerStatefulWidget {
  const VerificationDetailPage({super.key, required this.ngoId});
  final String ngoId;
  @override
  ConsumerState<VerificationDetailPage> createState() => _VerificationDetailState();
}

class _VerificationDetailState extends ConsumerState<VerificationDetailPage> {
  final _message = TextEditingController();
  final _note = TextEditingController();
  bool _confirm80g = false;

  Future<void> _decide(String decision) async {
    final t = L10n.of(context);
    try {
      await ref.read(apiProvider).post('/api/v1/admin/ngos/${widget.ngoId}/verification', body: {
        'decision': decision,
        'messageToNgo': _message.text.trim(),
        'internalNote': _note.text.trim(),
        'confirm80g': _confirm80g,
      });
      ref.invalidate(_queueProvider);
      if (mounted) {
        showSnack(context, t.saved);
        context.pop();
      }
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  /// ADMIN-003: open an uploaded document in the device viewer.
  Future<void> _openDocument(Map<String, dynamic> d) async {
    try {
      final bytes = await ref.read(apiProvider).bytes('/api/v1/ngos/${widget.ngoId}/documents/${d['id']}/content');
      final dir = await getTemporaryDirectory();
      final name = '${d['fileName'] ?? d['id']}'.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
      final f = File('${dir.path}/$name');
      await f.writeAsBytes(bytes, flush: true);
      final r = await OpenFilex.open(f.path);
      if (r.type != ResultType.done && mounted) showSnack(context, r.message, error: true);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final data = ref.watch(_adminNgoProvider(widget.ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.review)),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_adminNgoProvider(widget.ngoId))),
        data: (n) {
          final a = n['address'] as Map?;
          final docs = (n['documents'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            AppCard(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${n['legalName']}', style: Theme.of(context).textTheme.titleLarge),
                const Divider(),
                KeyValueRow(t.registrationType, registrationTypeLabel(t, n['registrationType'] as String?)),
                KeyValueRow(t.registrationNumber, n['registrationNumber'] as String?),
                KeyValueRow(t.registrationDate, formatDate(n['registrationDate'])),
                KeyValueRow(t.registrationState, n['registrationState'] as String?),
                KeyValueRow(t.darpanId, n['darpanId'] as String?),
                KeyValueRow(t.pan, n['pan'] as String?),
                KeyValueRow(t.tax80g, n['tax80gNumber'] as String?),
                KeyValueRow(t.officialEmail, n['officialEmail'] as String?),
                KeyValueRow(t.address, a == null ? null : '${a['line1']}, ${a['city']}, ${a['district']}, ${a['state']} ${a['pinCode']}'),
                for (final c in (n['contacts'] as List? ?? const []))
                  KeyValueRow(humanizeCode('${c['kind']}'), '${c['name']} ${c['email'] ?? ''}'),
              ]),
            ),
            SectionHeader(t.documents),
            for (final d in docs)
              ListTile(
                leading: const Icon(Icons.description_outlined),
                title: Text(documentTypeLabel(t, d['type'] as String?)),
                subtitle: Text('${d['fileName']} · ${humanizeCode('${d['status']}')}'),
                trailing: const Icon(Icons.open_in_new),
                onTap: () => _openDocument(d),
              ),
            SectionHeader(t.review),
            TextField(controller: _message, maxLines: 3, decoration: InputDecoration(labelText: t.messageToNgo)),
            const SizedBox(height: AppSpacing.sm),
            TextField(controller: _note, maxLines: 2, decoration: InputDecoration(labelText: t.internalNote)),
            if (n['tax80gNumber'] != null)
              CheckboxListTile(value: _confirm80g, onChanged: (v) => setState(() => _confirm80g = v ?? false),
                  title: Text('80G ${t.verified}'), contentPadding: EdgeInsets.zero),
            const SizedBox(height: AppSpacing.md),
            BusyButton(label: t.approve, icon: Icons.check, onPressed: () => _decide('APPROVE')),
            const SizedBox(height: AppSpacing.sm),
            BusyButton(label: t.requestInfo, outlined: true, onPressed: () => _decide('REQUEST_INFO')),
            TextButton(onPressed: () => _decide('REJECT'), child: Text(t.reject, style: const TextStyle(color: AppColors.error))),
          ]);
        },
      ),
    );
  }
}
