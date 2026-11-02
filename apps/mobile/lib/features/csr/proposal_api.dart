import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../localization/app_localizations.dart';

/// CSR funding proposals (Enterprise, owner decision 2026-11-02): written from a campaign, sent as a frozen PDF,
/// followed up with notes, closed as funded / declined / withdrawn; a funded one leads to the utilisation certificate.

String proposalStatusLabel(L10n t, Object? s) => switch (s) {
      'DRAFT' => t.prStatusDraft,
      'SENT' => t.prStatusSent,
      'FUNDED' => t.prStatusFunded,
      'DECLINED' => t.prStatusDeclined,
      'WITHDRAWN' => t.prStatusWithdrawn,
      _ => '$s',
    };

BadgeTone proposalStatusTone(Object? s) => switch (s) {
      'FUNDED' => BadgeTone.success,
      'SENT' => BadgeTone.info,
      'DRAFT' => BadgeTone.warning,
      'DECLINED' => BadgeTone.error,
      _ => BadgeTone.neutral,
    };

String proposalEventLabel(L10n t, Object? k) => switch (k) {
      'CREATED' => t.prEventCreated,
      'SENT' => t.prEventSent,
      'NOTE' => t.prEventNote,
      'FUNDED' => t.prEventFunded,
      'DECLINED' => t.prEventDeclined,
      'WITHDRAWN' => t.prEventWithdrawn,
      'COPIED' => t.prEventCopied,
      _ => '$k',
    };

final proposalListProvider = FutureProvider.autoDispose.family<Map<String, dynamic>, (String, String?)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/csr-proposals', query: {if (k.$2 != null) 'campaignId': k.$2});
});

final proposalProvider = FutureProvider.autoDispose.family<Map<String, dynamic>, (String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/csr-proposals/${k.$2}');
});

void invalidateProposals(WidgetRef ref, String ngoId, [String? id]) {
  ref.invalidate(proposalListProvider);
  if (id != null) ref.invalidate(proposalProvider((ngoId, id)));
}
