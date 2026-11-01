import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';

/// CSR compliance pack (Enterprise, owner decision 2026-11-02): vault documents shared with a company through one
/// expiring link. Nothing is copied; sharing freezes the exact files. The company's page and downloads are served by
/// the server; the app builds, shares, watches and stops packs.

/// Papers a CSR team asks for, in reading order (server: CsrRules.KINDS).
const csrKinds = [
  'REGISTRATION', 'PAN', 'REG_12A', 'REG_80G', 'CSR1', 'AUDITED_ACCOUNTS', 'ANNUAL_REPORT', 'BOARD_LIST', 'FCRA',
  'UTILISATION', 'PROPOSAL', 'OTHER',
];
const csrRequiredKinds = {'REGISTRATION', 'PAN', 'REG_12A', 'REG_80G', 'CSR1', 'AUDITED_ACCOUNTS'};

String csrKindLabel(L10n t, Object? k) => switch (k) {
      'REGISTRATION' => t.csrKindRegistration,
      'PAN' => t.csrKindPan,
      'REG_12A' => t.csrKind12a,
      'REG_80G' => t.csrKind80g,
      'CSR1' => t.csrKindCsr1,
      'AUDITED_ACCOUNTS' => t.csrKindAudited,
      'ANNUAL_REPORT' => t.csrKindAnnual,
      'BOARD_LIST' => t.csrKindBoard,
      'FCRA' => t.csrKindFcra,
      'UTILISATION' => t.csrKindUtilisation,
      'PROPOSAL' => t.csrKindProposal,
      _ => t.csrKindOther,
    };

String csrStatusLabel(L10n t, Object? s) => switch (s) {
      'DRAFT' => t.csrStatusDraft,
      'SHARED' => t.csrStatusShared,
      'EXPIRED' => t.csrStatusExpired,
      'REVOKED' => t.csrStatusRevoked,
      _ => '$s',
    };

BadgeTone csrStatusTone(Object? s) => switch (s) {
      'SHARED' => BadgeTone.success,
      'DRAFT' => BadgeTone.warning,
      'EXPIRED' || 'REVOKED' => BadgeTone.error,
      _ => BadgeTone.neutral,
    };

/// Readable text for a readiness code from the server ("MISSING: CSR1", "EXPIRES_SOON: title (2026-11-22)" ...).
String csrIssueLabel(L10n t, String issue) {
  final i = issue.indexOf(':');
  final code = i < 0 ? issue : issue.substring(0, i);
  final rest = i < 0 ? '' : issue.substring(i + 1).trim();
  return switch (code) {
    'MISSING' => t.csrIssueMissing(csrKindLabel(t, rest)),
    'EXPIRES_SOON' => t.csrIssueExpiresSoon(rest),
    'DOCUMENT_EXPIRED' => t.csrIssueExpired(rest),
    'DOCUMENT_REMOVED' => t.csrIssueRemoved(rest),
    'EMPTY' => t.csrIssueEmpty,
    _ => issue,
  };
}

List<Map<String, dynamic>> csrMaps(Object? l) => (l as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

final csrOverviewProvider = FutureProvider.autoDispose.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/csr/overview');
});

final csrPackProvider = FutureProvider.autoDispose.family<Map<String, dynamic>, (String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/csr/packs/${k.$2}');
});

final csrViewsProvider = FutureProvider.autoDispose.family<List<Map<String, dynamic>>, (String, String)>((ref, k) async {
  ref.watch(sessionKeyProvider);
  return csrMaps(await ref.read(apiProvider).getList('/api/v1/ngos/${k.$1}/csr/packs/${k.$2}/views'));
});

final csrSuggestionsProvider = FutureProvider.autoDispose.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/csr/suggestions');
});

void invalidateCsr(WidgetRef ref, String ngoId, [String? packId]) {
  ref.invalidate(csrOverviewProvider(ngoId));
  if (packId != null) {
    ref.invalidate(csrPackProvider((ngoId, packId)));
    ref.invalidate(csrViewsProvider((ngoId, packId)));
  }
}

Future<void> shareCsrLink(BuildContext context, Map<String, dynamic> p) async {
  final url = p['link'] as String?;
  if (url == null) return;
  final t = L10n.of(context);
  final box = context.findRenderObject() as RenderBox?;
  await SharePlus.instance.share(ShareParams(
    text: t.csrShareText('${p['recipientName']}', url),
    subject: '${p['title']}',
    sharePositionOrigin: box == null ? null : box.localToGlobal(Offset.zero) & box.size,
  ));
}

Future<void> copyCsrLink(BuildContext context, Map<String, dynamic> p) async {
  final url = p['link'] as String?;
  if (url == null) return;
  await Clipboard.setData(ClipboardData(text: url));
  if (context.mounted) showSnack(context, L10n.of(context).csrLinkCopied);
}

Color csrKindColor(Object? k) => csrRequiredKinds.contains(k) ? AppColors.primary : AppColors.textMuted;
