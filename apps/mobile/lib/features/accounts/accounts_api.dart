import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';

/// Campaign accounts (owner request 2026-10-29): expenses with bills, admin approval, budget vs actual, vendors,
/// staff cash advances and the account sheet. Server rules: campaign-project-service AccountRules.
abstract final class ExpenseStatus {
  static const submitted = 'SUBMITTED';
  static const approved = 'APPROVED';
  static const rejected = 'REJECTED';
  static const voided = 'VOID';
}

const paymentModes = ['CASH', 'UPI', 'BANK_TRANSFER', 'CHEQUE', 'CARD'];
const maxBills = 5;

typedef CampaignKey = (String ngoId, String campaignId);

String _base(CampaignKey k) => '/api/v1/ngos/${k.$1}/campaigns/${k.$2}';

List<Map<String, dynamic>> _maps(Object? l) => (l as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

final accountSheetProvider = FutureProvider.family<Map<String, dynamic>, CampaignKey>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('${_base(k)}/accounts');
});

final campaignExpensesProvider = FutureProvider.family<List<Map<String, dynamic>>, CampaignKey>((ref, k) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('${_base(k)}/expenses'));
});

final expenseProvider = FutureProvider.family<Map<String, dynamic>, (String, String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/campaigns/${k.$2}/expenses/${k.$3}');
});

final budgetProvider = FutureProvider.family<Map<String, dynamic>, CampaignKey>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('${_base(k)}/budget');
});

final pendingExpensesProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/expenses/pending');
});

final vendorsProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('/api/v1/ngos/$ngoId/vendors', query: {'includeInactive': true}));
});

final staffBalancesProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('/api/v1/ngos/$ngoId/staff-money'));
});

/// One member's cash ledger ('me' for the signed-in member).
final staffLedgerProvider = FutureProvider.family<Map<String, dynamic>, (String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/staff-money/${k.$2}');
});

/// Bill image / PDF bytes (team only). Key: ngoId, campaignId, expenseId, attachmentId.
final billBytesProvider = FutureProvider.family<Uint8List, (String, String, String, String)>((ref, k) async {
  ref.watch(sessionKeyProvider);
  final b = await ref.read(apiProvider).bytes('/api/v1/ngos/${k.$1}/campaigns/${k.$2}/expenses/${k.$3}/attachments/${k.$4}');
  return Uint8List.fromList(b);
});

void invalidateAccounts(WidgetRef ref, String ngoId, String campaignId, [String? expenseId]) {
  ref.invalidate(accountSheetProvider((ngoId, campaignId)));
  ref.invalidate(campaignExpensesProvider((ngoId, campaignId)));
  ref.invalidate(pendingExpensesProvider(ngoId));
  ref.invalidate(budgetProvider((ngoId, campaignId)));
  ref.invalidate(staffLedgerProvider);
  ref.invalidate(staffBalancesProvider(ngoId));
  if (expenseId != null) ref.invalidate(expenseProvider((ngoId, campaignId, expenseId)));
}

/// A bill picked on the phone, not yet uploaded.
class PickedBill {
  PickedBill(this.bytes, this.name, this.isPdf);
  final Uint8List bytes;
  final String name;
  final bool isPdf;
}

/// Creates an expense with its bills in one request (multipart: JSON part "expense" + "files").
Future<Map<String, dynamic>> createExpense(Api api, CampaignKey k, Map<String, dynamic> body, List<PickedBill> bills) async {
  final form = FormData();
  form.fields.add(MapEntry('expense', jsonEncode(body)));
  for (final b in bills) {
    form.files.add(MapEntry('files', MultipartFile.fromBytes(b.bytes, filename: b.name,
        contentType: DioMediaType.parse(b.isPdf ? 'application/pdf' : 'image/jpeg'))));
  }
  try {
    final r = await api.dio.post('${_base(k)}/expenses', data: form);
    return Map<String, dynamic>.from(r.data as Map);
  } catch (e) {
    throw ApiError.from(e);
  }
}

String paymentModeLabel(L10n t, String? mode) => switch (mode) {
      'CASH' => t.accModeCash,
      'UPI' => t.accModeUpi,
      'BANK_TRANSFER' => t.accModeBank,
      'CHEQUE' => t.accModeCheque,
      'CARD' => t.accModeCard,
      _ => mode ?? '—',
    };

String expenseStatusLabel(L10n t, String? s) => switch (s) {
      ExpenseStatus.submitted => t.accStatusSubmitted,
      ExpenseStatus.approved => t.accStatusApproved,
      ExpenseStatus.rejected => t.accStatusRejected,
      ExpenseStatus.voided => t.accStatusVoid,
      _ => s ?? '',
    };

Color expenseStatusColor(String? s) => switch (s) {
      ExpenseStatus.approved => AppColors.success,
      ExpenseStatus.submitted => const Color(0xFFB45309),
      ExpenseStatus.rejected => AppColors.error,
      _ => AppColors.textMuted,
    };

String moneyKindLabel(L10n t, String? kind) => switch (kind) {
      'ADVANCE' => t.accKindAdvance,
      'EXPENSE' => t.accKindExpense,
      'RETURN' => t.accKindReturn,
      'REIMBURSEMENT' => t.accKindReimbursement,
      'EXPENSE_VOID' => t.accKindExpenseVoid,
      _ => kind ?? '',
    };

num amountOf(Object? v) => num.tryParse('${v ?? 0}') ?? 0;

/// Downloads a file from the API, keeps it in the app's documents folder and lets the user open or share it
/// (WhatsApp / e-mail to the accountant).
Future<void> downloadAndOffer(BuildContext context, WidgetRef ref, String path, String fileName, String mime) async {
  final t = L10n.of(context);
  try {
    final bytes = await ref.read(apiProvider).bytes(path);
    final dir = await getApplicationDocumentsDirectory();
    final f = File('${dir.path}/$fileName');
    await f.writeAsBytes(bytes, flush: true);
    if (!context.mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (c) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(title: Text(fileName, style: const TextStyle(fontWeight: FontWeight.w600)), subtitle: Text(t.accFileReady)),
          ListTile(
            leading: const Icon(Icons.open_in_new, color: AppColors.primary),
            title: Text(t.accOpenFile),
            onTap: () async {
              Navigator.pop(c);
              final r = await OpenFilex.open(f.path, type: mime);
              if (r.type != ResultType.done && context.mounted) showSnack(context, t.accNoAppToOpen, error: true);
            },
          ),
          ListTile(
            leading: const Icon(Icons.share_outlined, color: AppColors.primary),
            title: Text(t.accShareFile),
            onTap: () async {
              Navigator.pop(c);
              await SharePlus.instance.share(ShareParams(files: [XFile(f.path, mimeType: mime)], subject: fileName));
            },
          ),
        ]),
      ),
    );
  } catch (e) {
    if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
  }
}
