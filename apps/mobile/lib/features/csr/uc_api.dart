import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../localization/app_localizations.dart';

/// Utilisation certificates (CSR, Enterprise, owner decision 2026-11-02): prepared from a campaign's accounts, signed
/// by the NGO, certified by its chartered accountant through a link, then kept in the vault (category CSR).

String ucStatusLabel(L10n t, Object? s) => switch (s) {
      'DRAFT' => t.ucStatusDraft,
      'AWAITING_CA' => t.ucStatusAwaitingCa,
      'CA_LINK_EXPIRED' => t.ucStatusLinkExpired,
      'CA_CERTIFIED' => t.ucStatusCertified,
      'CANCELLED' => t.ucStatusCancelled,
      _ => '$s',
    };

BadgeTone ucStatusTone(Object? s) => switch (s) {
      'CA_CERTIFIED' => BadgeTone.success,
      'DRAFT' || 'AWAITING_CA' => BadgeTone.warning,
      'CA_LINK_EXPIRED' || 'CANCELLED' => BadgeTone.error,
      _ => BadgeTone.neutral,
    };

final ucListProvider = FutureProvider.autoDispose.family<Map<String, dynamic>, (String, String?)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/ucs', query: {if (k.$2 != null) 'campaignId': k.$2});
});

final ucProvider = FutureProvider.autoDispose.family<Map<String, dynamic>, (String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/ucs/${k.$2}');
});

void invalidateUcs(WidgetRef ref, String ngoId, [String? id]) {
  ref.invalidate(ucListProvider);
  if (id != null) ref.invalidate(ucProvider((ngoId, id)));
}

Future<void> shareCaLink(BuildContext context, Map<String, dynamic> uc) async {
  final url = uc['caLink'] as String?;
  if (url == null) return;
  final t = L10n.of(context);
  final box = context.findRenderObject() as RenderBox?;
  await SharePlus.instance.share(ShareParams(
    text: t.ucShareText('${uc['number']}', '${uc['funderName']}', url),
    subject: '${uc['number']}',
    sharePositionOrigin: box == null ? null : box.localToGlobal(Offset.zero) & box.size,
  ));
}

Future<void> copyCaLink(BuildContext context, Map<String, dynamic> uc) async {
  final url = uc['caLink'] as String?;
  if (url == null) return;
  await Clipboard.setData(ClipboardData(text: url));
  if (context.mounted) showSnack(context, L10n.of(context).csrLinkCopied);
}
