import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';
import '../../localization/app_localizations.dart';

/// Campaign workflow (owner decision 2026-10-29): any team member drafts and sends for approval;
/// only NGO administrators approve (publish), request changes, edit a live campaign or close it.
abstract final class CampaignStatus {
  static const draft = 'DRAFT';
  static const pending = 'PENDING_APPROVAL';
  static const live = 'PUBLISHED';
  static const closed = 'CLOSED';
  static const archived = 'ARCHIVED';
}

/// Cause codes accepted by campaign-service (same list as NGO areas of activity).
const campaignCauses = [
  'EDUCATION', 'HEALTH', 'CHILD_WELFARE', 'WOMEN_EMPOWERMENT', 'ELDERLY_CARE', 'DISABILITY', 'HUNGER_NUTRITION',
  'WATER_SANITATION', 'ENVIRONMENT', 'ANIMAL_WELFARE', 'DISASTER_RELIEF', 'RURAL_DEVELOPMENT', 'LIVELIHOOD',
  'ARTS_CULTURE', 'SPORTS', 'OTHER',
];

class CampaignFilter {
  const CampaignFilter(this.ngoId, this.status);
  final String ngoId;
  final String? status;
  @override
  bool operator ==(Object other) => other is CampaignFilter && other.ngoId == ngoId && other.status == status;
  @override
  int get hashCode => Object.hash(ngoId, status);
}

final ngoCampaignsProvider = FutureProvider.family<List<Map<String, dynamic>>, CampaignFilter>((ref, f) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson('/api/v1/ngos/${f.ngoId}/campaigns', query: {'status': f.status, 'size': 50});
  return (r['items'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final campaignSummaryProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/campaigns/summary');
});

final ngoCampaignProvider = FutureProvider.family<Map<String, dynamic>, (String, String)>((ref, key) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${key.$1}/campaigns/${key.$2}');
});

final campaignDonationsProvider = FutureProvider.family<List<Map<String, dynamic>>, (String, String)>((ref, key) async {
  ref.watch(sessionKeyProvider);
  final list = await ref.read(apiProvider).getList('/api/v1/ngos/${key.$1}/campaigns/${key.$2}/donations');
  return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

/// Cover of a not-yet-public campaign (needs the team member's token). Key: ngoId, campaignId, imageVersion, size.
final teamCoverProvider = FutureProvider.family<Uint8List, (String, String, int, String)>((ref, k) async {
  ref.watch(sessionKeyProvider);
  final bytes = await ref.read(apiProvider).bytes('/api/v1/ngos/${k.$1}/campaigns/${k.$2}/image', query: {'size': k.$4});
  return Uint8List.fromList(bytes);
});

/// userId -> display name for the current NGO team (to show who drafted / approved).
final teamNamesProvider = FutureProvider.family<Map<String, String>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  try {
    final list = await ref.read(apiProvider).getList('/api/v1/ngos/$ngoId/staff');
    return {
      for (final m in list.cast<Map>())
        '${m['userId']}': ((m['displayName'] as String?)?.trim().isNotEmpty ?? false) ? m['displayName'] as String : '${m['email']}',
    };
  } catch (_) {
    return const {};
  }
});

class PublicCampaignQuery {
  const PublicCampaignQuery({this.ngoId, this.cause, this.q = '', this.size = 20});
  final String? ngoId;
  final String? cause;
  final String q;
  final int size;
  @override
  bool operator ==(Object other) =>
      other is PublicCampaignQuery && other.ngoId == ngoId && other.cause == cause && other.q == q && other.size == size;
  @override
  int get hashCode => Object.hash(ngoId, cause, q, size);
}

final publicCampaignsProvider = FutureProvider.family<List<Map<String, dynamic>>, PublicCampaignQuery>((ref, p) async {
  final r = await ref.read(apiProvider).getJson('/api/v1/public/campaigns',
      query: {'ngoId': p.ngoId, 'cause': p.cause, 'q': p.q, 'size': p.size});
  return (r['items'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final publicCampaignProvider = FutureProvider.family<Map<String, dynamic>, String>(
    (ref, id) => ref.read(apiProvider).getJson('/api/v1/public/campaigns/$id'));

/// Refreshes everything that shows campaigns of [ngoId] after a change.
void invalidateCampaigns(WidgetRef ref, String ngoId, [String? campaignId]) {
  ref.invalidate(ngoCampaignsProvider);
  ref.invalidate(campaignSummaryProvider(ngoId));
  if (campaignId != null) {
    ref.invalidate(ngoCampaignProvider((ngoId, campaignId)));
    ref.invalidate(campaignDonationsProvider((ngoId, campaignId)));
  }
  ref.invalidate(publicCampaignsProvider);
}

String campaignStatusText(L10n t, String status) => t.campaignStatusLabel(status);

/// Parses an API date (yyyy-MM-dd) as a calendar date.
DateTime? parseDay(Object? v) {
  if (v == null) return null;
  final d = DateTime.tryParse('$v');
  return d == null ? null : DateTime(d.year, d.month, d.day);
}

DateTime today() {
  final n = DateTime.now();
  return DateTime(n.year, n.month, n.day);
}

String isoDay(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// "12 days left" / "Ends today" / "Starts 3 Nov" / "Ended 1 Oct" for a campaign's dates.
String timingText(L10n t, Map<String, dynamic> c, String Function(Object?) fmt) {
  final start = parseDay(c['startDate']);
  final end = parseDay(c['endDate']);
  final now = today();
  if (start != null && now.isBefore(start)) return t.startsOn(fmt(c['startDate']));
  if (c['status'] == CampaignStatus.closed && c['closedAt'] != null) return t.endedOn(fmt(c['closedAt']));
  if (end == null) return '';
  if (now.isAfter(end) || c['status'] == CampaignStatus.closed || c['phase'] == 'ENDED') return t.endedOn(fmt(c['endDate']));
  final days = end.difference(now).inDays;
  return days == 0 ? t.endsToday : t.daysLeft(days);
}

double progressOf(Map<String, dynamic> c) {
  final goal = num.tryParse('${c['goalAmount'] ?? 0}') ?? 0;
  final raised = num.tryParse('${c['raisedAmount'] ?? 0}') ?? 0;
  return goal <= 0 ? 0 : (raised / goal).clamp(0, 1).toDouble();
}

Color statusColor(String status) => switch (status) {
      CampaignStatus.live => const Color(0xFF087A4B),
      CampaignStatus.pending => const Color(0xFFB45309),
      CampaignStatus.draft => const Color(0xFF5B6478),
      _ => const Color(0xFF5B6478),
    };
