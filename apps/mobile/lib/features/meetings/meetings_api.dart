import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';
import '../../localization/app_localizations.dart';

/// Meetings and minutes (owner request 2026-11-01): AGM / EGM / board / committee / staff meetings, attendance,
/// minutes, numbered resolutions and action items. Administrators manage; approved minutes are final and filed as a
/// PDF in the document vault.
const meetingTypes = ['AGM', 'EGM', 'BOARD', 'COMMITTEE', 'STAFF', 'OTHER'];
const meetingModes = ['IN_PERSON', 'ONLINE', 'HYBRID'];

abstract final class MeetingStatus {
  static const scheduled = 'SCHEDULED';
  static const draft = 'MINUTES_DRAFT';
  static const approved = 'MINUTES_APPROVED';
  static const cancelled = 'CANCELLED';
}

String meetingTypeLabel(L10n t, String? type) => switch (type) {
      'AGM' => t.meetAgm,
      'EGM' => t.meetEgm,
      'BOARD' => t.meetBoard,
      'COMMITTEE' => t.meetCommittee,
      'STAFF' => t.meetStaff,
      _ => t.meetOther,
    };

String meetingTypeShort(String? type) => switch (type) {
      'AGM' => 'AGM',
      'EGM' => 'EGM',
      'BOARD' => 'BOARD',
      'COMMITTEE' => 'COMM',
      'STAFF' => 'STAFF',
      _ => 'MEET',
    };

Color meetingTypeColor(String? type) => switch (type) {
      'AGM' => const Color(0xFF087A4B),
      'EGM' => const Color(0xFFC2410C),
      'BOARD' => const Color(0xFF5B21B6),
      'COMMITTEE' => const Color(0xFF0E7490),
      'STAFF' => const Color(0xFF1D4ED8),
      _ => const Color(0xFF5B6478),
    };

String meetingStatusLabel(L10n t, String? s) => switch (s) {
      MeetingStatus.scheduled => t.meetStatusScheduled,
      MeetingStatus.draft => t.meetStatusDraft,
      MeetingStatus.approved => t.meetStatusApproved,
      MeetingStatus.cancelled => t.meetStatusCancelled,
      _ => s ?? '',
    };

Color meetingStatusColor(String? s) => switch (s) {
      MeetingStatus.approved => const Color(0xFF087A4B),
      MeetingStatus.draft => const Color(0xFFB45309),
      MeetingStatus.cancelled => const Color(0xFFC62828),
      _ => const Color(0xFF1D4ED8),
    };

String modeLabel(L10n t, String? m) => switch (m) {
      'ONLINE' => t.meetOnline,
      'HYBRID' => t.meetHybrid,
      _ => t.meetInPerson,
    };

String resultLabel(L10n t, String? r) => switch (r) {
      'REJECTED' => t.meetRejected,
      'DEFERRED' => t.meetDeferred,
      _ => t.meetPassed,
    };

List<Map<String, dynamic>> _maps(Object? l) => (l as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

final meetingsProvider = FutureProvider.family<List<Map<String, dynamic>>, (String, String)>((ref, k) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('/api/v1/ngos/${k.$1}/meetings', query: {'when': k.$2}));
});

final meetingSummaryProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/meetings/summary');
});

final meetingProvider = FutureProvider.family<Map<String, dynamic>, (String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/meetings/${k.$2}');
});

/// (ngoId, mine) -> action items with their meeting title.
final actionItemsProvider = FutureProvider.family<List<Map<String, dynamic>>, (String, bool)>((ref, k) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('/api/v1/ngos/${k.$1}/action-items', query: {'mine': '${k.$2}'}));
});

void invalidateMeetings(WidgetRef ref, String ngoId, [String? id]) {
  ref.invalidate(meetingsProvider);
  ref.invalidate(meetingSummaryProvider(ngoId));
  ref.invalidate(actionItemsProvider);
  if (id != null) ref.invalidate(meetingProvider((ngoId, id)));
}
