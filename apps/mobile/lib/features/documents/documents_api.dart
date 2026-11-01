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

/// Document vault (owner decision 2026-11-01): one place for every NGO file, kept in the shared object store.
/// Administrators see everything; team members see their own uploads and documents shared with the team.
const documentCategories = [
  'LEGAL_REGISTRATION', 'TAX_COMPLIANCE', 'FINANCE', 'CAMPAIGN', 'PROJECT', 'AGREEMENT', 'MEETING', 'HR',
  'PHOTOS_REPORTS', 'RECEIPT', 'CSR', 'OTHER',
];

/// Categories that are only for the uploader and administrators unless shared on purpose.
const confidentialCategories = {'LEGAL_REGISTRATION', 'HR', 'MEETING', 'AGREEMENT'};

const documentFileTypes = ['PDF', 'IMAGE', 'WORD', 'EXCEL', 'SLIDES'];

String categoryLabel(L10n t, String? c) => switch (c) {
      'LEGAL_REGISTRATION' => t.docCatLegal,
      'TAX_COMPLIANCE' => t.docCatTax,
      'FINANCE' => t.docCatFinance,
      'CAMPAIGN' => t.docCatCampaign,
      'PROJECT' => t.docCatProject,
      'AGREEMENT' => t.docCatAgreement,
      'MEETING' => t.docCatMeeting,
      'HR' => t.docCatHr,
      'PHOTOS_REPORTS' => t.docCatPhotos,
      'RECEIPT' => t.docCatReceipt,
      'CSR' => t.docCatCsr,
      _ => t.docCatOther,
    };

IconData categoryIcon(String? c) => switch (c) {
      'LEGAL_REGISTRATION' => Icons.gavel_outlined,
      'TAX_COMPLIANCE' => Icons.verified_outlined,
      'FINANCE' => Icons.account_balance_outlined,
      'CAMPAIGN' => Icons.campaign_outlined,
      'PROJECT' => Icons.work_outline,
      'AGREEMENT' => Icons.handshake_outlined,
      'MEETING' => Icons.groups_outlined,
      'HR' => Icons.badge_outlined,
      'PHOTOS_REPORTS' => Icons.photo_library_outlined,
      'RECEIPT' => Icons.receipt_long_outlined,
      'CSR' => Icons.handshake_outlined,
      _ => Icons.folder_outlined,
    };

Color categoryColor(String? c) => switch (c) {
      'LEGAL_REGISTRATION' => const Color(0xFF5B21B6),
      'TAX_COMPLIANCE' => const Color(0xFF087A4B),
      'FINANCE' => const Color(0xFF1D4ED8),
      'CAMPAIGN' => const Color(0xFFC2410C),
      'PROJECT' => const Color(0xFF0E7490),
      'AGREEMENT' => const Color(0xFF7C3AED),
      'MEETING' => const Color(0xFFB45309),
      'HR' => const Color(0xFFBE185D),
      'PHOTOS_REPORTS' => const Color(0xFF15803D),
      'RECEIPT' => const Color(0xFF0369A1),
      'CSR' => const Color(0xFF0F766E),
      _ => const Color(0xFF5B6478),
    };

String fileTypeLabel(L10n t, String? type) => switch (type) {
      'PDF' => 'PDF',
      'IMAGE' => t.docTypePhoto,
      'WORD' => 'Word',
      'EXCEL' => 'Excel',
      'SLIDES' => 'PowerPoint',
      _ => t.docTypeOther,
    };

IconData fileTypeIcon(String? type) => switch (type) {
      'PDF' => Icons.picture_as_pdf_outlined,
      'IMAGE' => Icons.image_outlined,
      'WORD' => Icons.description_outlined,
      'EXCEL' => Icons.grid_on_outlined,
      'SLIDES' => Icons.slideshow_outlined,
      _ => Icons.insert_drive_file_outlined,
    };

String sizeLabel(Object? bytes) {
  final b = (bytes as num?)?.toDouble() ?? 0;
  if (b >= 1024 * 1024 * 1024) return '${(b / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  if (b >= 1024 * 1024) return '${(b / (1024 * 1024)).toStringAsFixed(1)} MB';
  return '${(b / 1024).ceil().clamp(1, 1023)} KB';
}

/// Search criteria for the vault list.
class DocQuery {
  const DocQuery({this.q = '', this.category, this.type, this.from, this.to, this.tag, this.mine = false, this.sort = 'new',
      this.linkType, this.linkId});
  final String q;
  final String? category;
  final String? type;
  final String? from;
  final String? to;
  final String? tag;
  final bool mine;
  final String sort;
  final String? linkType;
  final String? linkId;

  DocQuery copy({String? q, Object? category = _keep, Object? type = _keep, Object? from = _keep, Object? to = _keep,
          Object? tag = _keep, bool? mine, String? sort}) =>
      DocQuery(
        q: q ?? this.q,
        category: category == _keep ? this.category : category as String?,
        type: type == _keep ? this.type : type as String?,
        from: from == _keep ? this.from : from as String?,
        to: to == _keep ? this.to : to as String?,
        tag: tag == _keep ? this.tag : tag as String?,
        mine: mine ?? this.mine,
        sort: sort ?? this.sort,
        linkType: linkType,
        linkId: linkId,
      );

  bool get hasFilters => category != null || type != null || from != null || to != null || tag != null || mine;

  Map<String, dynamic> toQuery() => {
        'q': q.trim(), 'category': category, 'type': type, 'from': from, 'to': to, 'tag': tag,
        'mine': mine ? 'true' : null, 'sort': sort == 'new' ? null : sort, 'linkType': linkType, 'linkId': linkId, 'size': 50,
      };

  @override
  bool operator ==(Object o) =>
      o is DocQuery && o.q == q && o.category == category && o.type == type && o.from == from && o.to == to && o.tag == tag &&
      o.mine == mine && o.sort == sort && o.linkType == linkType && o.linkId == linkId;
  @override
  int get hashCode => Object.hash(q, category, type, from, to, tag, mine, sort, linkType, linkId);
}

const _keep = Object();

List<Map<String, dynamic>> _maps(Object? l) => (l as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

final docOverviewProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/library/overview');
});

final docSearchProvider = FutureProvider.family<Map<String, dynamic>, (String, DocQuery)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/library', query: k.$2.toQuery());
});

final documentProvider = FutureProvider.family<Map<String, dynamic>, (String, String)>((ref, k) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/${k.$1}/library/${k.$2}');
});

final docActivityProvider = FutureProvider.family<List<Map<String, dynamic>>, (String, String)>((ref, k) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('/api/v1/ngos/${k.$1}/library/${k.$2}/activity'));
});

final docBinProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  return _maps(await ref.read(apiProvider).getList('/api/v1/ngos/$ngoId/library/bin'));
});

/// Image bytes for previews (photos only).
final docBytesProvider = FutureProvider.family<Uint8List, (String, String)>((ref, k) async {
  ref.watch(sessionKeyProvider);
  return Uint8List.fromList(await ref.read(apiProvider).bytes('/api/v1/ngos/${k.$1}/library/${k.$2}/content'));
});

void invalidateDocuments(WidgetRef ref, String ngoId, [String? id]) {
  ref.invalidate(docOverviewProvider(ngoId));
  ref.invalidate(docSearchProvider);
  ref.invalidate(docBinProvider(ngoId));
  if (id != null) {
    ref.invalidate(documentProvider((ngoId, id)));
    ref.invalidate(docActivityProvider((ngoId, id)));
  }
}

/// Uploads one file with its details (multipart: "file" + JSON part "meta").
Future<Map<String, dynamic>> uploadDocument(Api api, String ngoId, Uint8List bytes, String fileName, Map<String, dynamic> meta) async {
  final form = FormData();
  form.fields.add(MapEntry('meta', jsonEncode(meta)));
  form.files.add(MapEntry('file', MultipartFile.fromBytes(bytes, filename: fileName)));
  try {
    final r = await api.dio.post('/api/v1/ngos/$ngoId/library', data: form);
    return Map<String, dynamic>.from(r.data as Map);
  } catch (e) {
    throw ApiError.from(e);
  }
}

String _ext(Map<String, dynamic> d) => switch (d['contentType']) {
      'application/pdf' => 'pdf',
      'image/jpeg' => 'jpg',
      'image/png' => 'png',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document' => 'docx',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' => 'xlsx',
      'application/vnd.openxmlformats-officedocument.presentationml.presentation' => 'pptx',
      'application/msword' => 'doc',
      'application/vnd.ms-excel' => 'xls',
      'application/vnd.ms-powerpoint' => 'ppt',
      _ => 'bin',
    };

String _safeName(String title) => title.replaceAll(RegExp(r'[\\/:*?"<>|]+'), ' ').trim();

/// Downloads the file to the phone's temporary folder (cleared by the system) and returns it.
Future<File> _download(WidgetRef ref, String ngoId, Map<String, dynamic> d) async {
  final bytes = await ref.read(apiProvider).bytes('/api/v1/ngos/$ngoId/library/${d['id']}/content');
  final dir = await getTemporaryDirectory();
  final f = File('${dir.path}/${_safeName('${d['title']}')}.${_ext(d)}');
  await f.writeAsBytes(bytes, flush: true);
  return f;
}

Future<void> openDocument(BuildContext context, WidgetRef ref, String ngoId, Map<String, dynamic> d) async {
  final t = L10n.of(context);
  try {
    final f = await _download(ref, ngoId, d);
    final r = await OpenFilex.open(f.path, type: '${d['contentType']}');
    if (r.type != ResultType.done && context.mounted) showSnack(context, t.accNoAppToOpen, error: true);
  } catch (e) {
    if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
  }
}

Future<void> shareDocument(BuildContext context, WidgetRef ref, String ngoId, Map<String, dynamic> d) async {
  try {
    final f = await _download(ref, ngoId, d);
    await SharePlus.instance.share(ShareParams(files: [XFile(f.path, mimeType: '${d['contentType']}')], subject: '${d['title']}'));
  } catch (e) {
    if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
  }
}

/// Coloured square with the category icon (list rows, grid tiles).
class CategoryBadge extends StatelessWidget {
  const CategoryBadge(this.category, {super.key, this.size = 40, this.fileType});
  final String? category;
  final String? fileType;
  final double size;
  @override
  Widget build(BuildContext context) {
    final c = categoryColor(category);
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(size / 4)),
      child: Icon(fileType != null ? fileTypeIcon(fileType) : categoryIcon(category), color: c, size: size * 0.55),
    );
  }
}

/// Storage used against the plan's limit.
class StorageBar extends StatelessWidget {
  const StorageBar({super.key, required this.used, required this.limit});
  final num used;
  final num limit;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final unlimited = limit < 0;
    final ratio = unlimited || limit == 0 ? 0.0 : (used / limit).clamp(0, 1).toDouble();
    final warn = ratio >= 0.8;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Icon(Icons.cloud_outlined, size: 18, color: AppColors.textMuted),
        const SizedBox(width: 6),
        Expanded(child: Text(unlimited ? t.docStorageUsedUnlimited(sizeLabel(used)) : t.docStorageUsed(sizeLabel(used), sizeLabel(limit)),
            style: const TextStyle(color: AppColors.textMuted, fontSize: 13))),
      ]),
      if (!unlimited) ...[
        const SizedBox(height: 6),
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(minHeight: 6, value: ratio, backgroundColor: AppColors.outline,
              color: warn ? AppColors.error : AppColors.primary),
        ),
      ],
    ]);
  }
}
