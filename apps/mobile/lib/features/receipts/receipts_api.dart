import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';

/// Donation receipts (receipt-service, owner decisions 2026-11-01): printed in English, the donor's name exactly as in
/// their profile, numbers PREFIX/<financial year>/000001, signatories, QR code to verify. The PDF is always made on the
/// server; the app lists, opens and shares it.
abstract final class ReceiptStatus {
  static const pending = 'PENDING';
  static const generating = 'GENERATING';
  static const generated = 'GENERATED';
  static const deliveryPending = 'DELIVERY_PENDING';
  static const delivered = 'DELIVERED';
  static const failed = 'GENERATION_FAILED';
  static const voided = 'VOIDED';
  static const reissued = 'REISSUED';

  static bool issued(Object? s) => s == generated || s == deliveryPending || s == delivered;
  static bool cancelled(Object? s) => s == voided || s == reissued;
}

/// Status groups used by the register filter (server side: ReceiptQueries.statuses).
const receiptGroups = ['ISSUED', 'PENDING', 'FAILED', 'CANCELLED'];

String receiptStatusLabel(L10n t, Object? s) => switch (s) {
      ReceiptStatus.pending || ReceiptStatus.generating => t.rcpStatusPreparing,
      ReceiptStatus.generated => t.rcpStatusIssued,
      ReceiptStatus.deliveryPending => t.rcpStatusSending,
      ReceiptStatus.delivered => t.rcpStatusSent,
      ReceiptStatus.failed => t.rcpStatusFailed,
      ReceiptStatus.voided => t.rcpStatusCancelled,
      ReceiptStatus.reissued => t.rcpStatusReplaced,
      _ => '$s',
    };

Color receiptStatusColor(Object? s) => switch (s) {
      ReceiptStatus.generated || ReceiptStatus.delivered || ReceiptStatus.deliveryPending => AppColors.success,
      ReceiptStatus.failed => AppColors.error,
      ReceiptStatus.voided || ReceiptStatus.reissued => const Color(0xFFC62828),
      _ => const Color(0xFFB45309),
    };

String receiptGroupLabel(L10n t, String g) => switch (g) {
      'ISSUED' => t.rcpGroupIssued,
      'PENDING' => t.rcpStatusPreparing,
      'FAILED' => t.rcpStatusFailed,
      'CANCELLED' => t.rcpGroupCancelled,
      _ => g,
    };

String channelLabel(L10n t, Object? c) => switch (c) {
      'ONLINE' => t.rcpChannelOnline,
      'CASH' => t.rcpChannelCash,
      'CHEQUE' => t.rcpChannelCheque,
      'BANK_TRANSFER' => t.rcpChannelBank,
      'UPI_DIRECT' => 'UPI',
      'IN_KIND_VALUE' => t.rcpChannelInKind,
      _ => '${c ?? ''}',
    };

/// Indian financial year of today: "2026-27".
String currentFy() {
  final d = DateTime.now();
  final s = d.month >= 4 ? d.year : d.year - 1;
  return '$s-${((s + 1) % 100).toString().padLeft(2, '0')}';
}

class ReceiptQuery {
  const ReceiptQuery({this.q = '', this.group, this.fy});
  final String q;
  final String? group;
  final String? fy;

  Map<String, dynamic> toQuery() => {'q': q, 'status': group, 'fy': fy, 'size': '50'};

  @override
  bool operator ==(Object other) => other is ReceiptQuery && other.q == q && other.group == group && other.fy == fy;
  @override
  int get hashCode => Object.hash(q, group, fy);
}

List<Map<String, dynamic>> _maps(Object? l) => (l as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

final receiptSummaryProvider = FutureProvider.family<Map<String, dynamic>, (String, String?)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/receipts/summary', query: {'fy': k.$2});
});

final receiptSearchProvider = FutureProvider.family<Map<String, dynamic>, (String, ReceiptQuery)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/receipts', query: k.$2.toQuery());
});

final receiptDetailProvider = FutureProvider.family<Map<String, dynamic>, (String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/receipts/${k.$2}');
});

final receiptSettingsProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/receipts/settings');
});

final signatoriesProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('/api/v1/ngos/$ngoId/receipts/signatories'));
});

/// Signature image (admins only), keyed by (ngoId, signatoryId, version) so a new upload refreshes it.
final signatureImageProvider = FutureProvider.family<List<int>, (String, String, int)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).bytes('/api/v1/ngos/${k.$1}/receipts/signatories/${k.$2}/signature');
});

/// Donor: own receipts (all organisations).
final myReceiptsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson('/api/v1/receipts/mine');
  return _maps(r['items']);
});

void invalidateReceipts(WidgetRef ref, String ngoId, [String? id]) {
  ref.invalidate(receiptSummaryProvider);
  ref.invalidate(receiptSearchProvider);
  if (id != null) ref.invalidate(receiptDetailProvider((ngoId, id)));
}

String receiptFileName(Object? number) => '${'${number ?? 'receipt'}'.replaceAll('/', '-')}.pdf';
