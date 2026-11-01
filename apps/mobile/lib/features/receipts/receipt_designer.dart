import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../accounts/accounts_api.dart' show downloadAndOffer;
import 'receipts_api.dart';

/// Custom receipt designer (spec §15.2, owner request 2026-11-01). Basic (Professional plan): the NGO's own receipt
/// picture as background (PNG/JPEG, max 5 MB, sharp enough to print), fields placed on it, one active design.
/// The PDF is always made on the server; "Preview" shows exactly what donors will get.

final designerLevelProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/receipt-templates/designer');
});

final templatesProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  return (await ref.read(apiProvider).getList('/api/v1/ngos/$ngoId/receipt-templates'))
      .map((e) => Map<String, dynamic>.from(e as Map)).where((t) => t['status'] != 'ARCHIVED').toList();
});

final templateBackgroundProvider = FutureProvider.family<Uint8List?, (String, String, int)>((ref, k) async {
  try {
    return Uint8List.fromList(await ref.read(apiProvider).bytes('/api/v1/ngos/${k.$1}/receipt-templates/${k.$2}/background'));
  } catch (_) {
    return null;
  }
});

const _maxBackgroundBytes = 5 * 1024 * 1024;

/// Picks a PNG/JPEG picture (camera, gallery or file). Null when cancelled; shows a message when too large.
Future<(Uint8List, String)?> pickReceiptPicture(BuildContext context, {required int maxBytes}) async {
  final t = L10n.of(context);
  final source = await showModalBottomSheet<String>(
    context: context,
    showDragHandle: true,
    builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
      ListTile(leading: const Icon(Icons.photo_camera_outlined), title: Text(t.rcdCamera), onTap: () => Navigator.pop(c, 'camera')),
      ListTile(leading: const Icon(Icons.photo_library_outlined), title: Text(t.rcdGallery), onTap: () => Navigator.pop(c, 'gallery')),
      ListTile(leading: const Icon(Icons.folder_open_outlined), title: Text(t.rcdFile), onTap: () => Navigator.pop(c, 'file')),
    ])),
  );
  if (source == null) return null;
  late Uint8List bytes;
  String name = 'receipt.jpg';
  if (source == 'file') {
    final f = await FilePicker.pickFile(type: FileType.custom, allowedExtensions: ['png', 'jpg', 'jpeg']);
    if (f == null) return null;
    bytes = Uint8List.fromList(await f.readAsBytes());
    name = f.name;
  } else {
    final x = await ImagePicker().pickImage(source: source == 'camera' ? ImageSource.camera : ImageSource.gallery, imageQuality: 90);
    if (x == null) return null;
    bytes = await x.readAsBytes();
    name = x.name;
  }
  final data = bytes;
  if (data.length > maxBytes) {
    if (context.mounted) showSnack(context, t.rcdTooBig, error: true);
    return null;
  }
  return (data, name);
}

// ============================================================================ overview

class ReceiptDesignPage extends ConsumerWidget {
  const ReceiptDesignPage({super.key});

  Future<void> _create(BuildContext context, WidgetRef ref, String ngoId) async {
    final t = L10n.of(context);
    final pic = await pickReceiptPicture(context, maxBytes: _maxBackgroundBytes);
    if (pic == null || !context.mounted) return;
    final api = ref.read(apiProvider);
    try {
      final created = await api.post('/api/v1/ngos/$ngoId/receipt-templates', body: {'name': t.rcdDefaultName});
      await api.upload('/api/v1/ngos/$ngoId/receipt-templates/${created['id']}/background', bytes: pic.$1, filename: pic.$2);
      ref.invalidate(templatesProvider(ngoId));
      if (context.mounted) context.push('/ngo/receipts/design/${created['id']}');
    } catch (e) {
      ref.invalidate(templatesProvider(ngoId));
      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final m = ref.watch(currentMembershipProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final level = ref.watch(designerLevelProvider(ngoId));
    final list = ref.watch(templatesProvider(ngoId));
    final canDesign = m?.can('RECEIPT_TEMPLATE_DESIGN') ?? false;
    return Scaffold(
      appBar: AppBar(title: Text(t.rcdTitle)),
      body: level.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(designerLevelProvider(ngoId))),
        data: (lv) {
          if (lv['level'] == 'NONE') {
            return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              EmptyState(icon: Icons.workspace_premium_outlined, title: t.rcdUpgradeTitle, message: t.rcdUpgradeHint,
                  action: FilledButton(onPressed: () => context.push('/ngo/subscription'), child: Text(t.rcdSeePlans))),
            ]);
          }
          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(templatesProvider(ngoId)),
            child: list.when(
              loading: () => const LoadingView(),
              error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(templatesProvider(ngoId)))]),
              data: (items) => ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
                Text(t.rcdIntro, style: const TextStyle(color: AppColors.textMuted)),
                const SizedBox(height: AppSpacing.md),
                for (final tp in items) _TemplateCard(ngoId: ngoId, tp: tp, canDesign: canDesign),
                if (items.isEmpty || lv['level'] == 'ADVANCED')
                  if (canDesign)
                    BusyButton(label: t.rcdCreate, icon: Icons.add_photo_alternate_outlined, onPressed: () => _create(context, ref, ngoId))
                  else
                    Text(t.rcdAdminsOnly, style: const TextStyle(color: AppColors.textMuted)),
                const SizedBox(height: AppSpacing.md),
                AppCard(
                  color: AppColors.primarySoft,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(t.rcdPictureRules, style: const TextStyle(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Text(t.rcdPictureRulesText),
                  ]),
                ),
              ]),
            ),
          );
        },
      ),
    );
  }
}

class _TemplateCard extends ConsumerWidget {
  const _TemplateCard({required this.ngoId, required this.tp, required this.canDesign});
  final String ngoId;
  final Map<String, dynamic> tp;
  final bool canDesign;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final active = tp['active'] == true;
    final published = tp['publishedVersion'] != null;
    final draftNewer = published && tp['currentVersion'] != tp['publishedVersion'];
    final base = '/api/v1/ngos/$ngoId/receipt-templates/${tp['id']}';
    Future<void> post(String path, String done) async {
      try {
        await ref.read(apiProvider).post('$base/$path');
        ref.invalidate(templatesProvider(ngoId));
        if (context.mounted) showSnack(context, done);
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
      }
    }

    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(child: Text('${tp['name']}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
            StatusBadge(active ? t.rcdInUse : published ? t.rcdNotInUse : t.rcdDraft,
                color: active ? AppColors.success : AppColors.textMuted),
          ]),
          if (draftNewer) Text(t.rcdUnpublishedChanges, style: const TextStyle(color: AppColors.warning, fontSize: 13)),
          const SizedBox(height: AppSpacing.sm),
          if (tp['hasBackground'] == true)
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: ref.watch(templateBackgroundProvider((ngoId, '${tp['id']}', (tp['currentVersion'] as num).toInt()))).when(
                    data: (b) => b == null ? const SizedBox.shrink() : Image.memory(b, height: 140, fit: BoxFit.cover),
                    loading: () => const SizedBox(height: 140, child: Center(child: CircularProgressIndicator())),
                    error: (_, __) => const SizedBox.shrink(),
                  ),
            ),
          const SizedBox(height: AppSpacing.sm),
          Wrap(spacing: 8, runSpacing: 8, children: [
            if (canDesign)
              FilledButton.icon(onPressed: () => context.push('/ngo/receipts/design/${tp['id']}'),
                  icon: const Icon(Icons.edit_outlined), label: Text(t.rcdEdit)),
            OutlinedButton.icon(
              onPressed: () => downloadAndOffer(context, ref, '$base/preview', 'receipt-design-preview.pdf', 'application/pdf'),
              icon: const Icon(Icons.preview_outlined), label: Text(t.rcdPreview)),
            if (canDesign && published && !active)
              OutlinedButton(onPressed: () => post('activate', t.rcdActivated), child: Text(t.rcdUseThis)),
            if (canDesign && active)
              OutlinedButton(onPressed: () => post('deactivate', t.rcdDeactivated), child: Text(t.rcdUseStandard)),
          ]),
        ]),
      ),
    );
  }
}

// ============================================================================ editor

/// Display values used on the canvas (the real PDF uses the donation's values).
Map<String, String> _sample(L10n t) => {
      'receiptNumber': 'ASF/${currentFy()}/000001',
      'date': '01 Nov 2026',
      'donorName': 'Sample Donor',
      'donorPan': 'ABCDE1234F',
      'donorAddress': '12 Sample Road, Pune 411001',
      'amount': '₹ 12,500.00',
      'amountInWords': 'Rupees Twelve Thousand Five Hundred Only',
      'mode': 'Online payment · Ref. pay_123',
      'towards': 'General donation',
      'ngoName': 'Your organisation',
      'ngoAddress': 'Registered address',
      'ngoPan': 'AAATA1234B',
      'ngoRegistrationNumber': 'F-12345',
      'ngo12a': '12A number',
      'tax80gText': 'This donation is eligible for deduction under section 80G of the Income-tax Act, 1961. 80G registration no. …',
      'thankYou': 'Thank you for your generous support.',
      'verifyCode': 'ABCD234567',
    };

String fieldLabel(L10n t, String key) => switch (key) {
      'receiptNumber' => t.rcdFReceiptNumber,
      'date' => t.rcdFDate,
      'donorName' => t.rcdFDonorName,
      'donorPan' => t.rcdFDonorPan,
      'donorAddress' => t.rcdFDonorAddress,
      'amount' => t.rcdFAmount,
      'amountInWords' => t.rcdFAmountInWords,
      'mode' => t.rcdFMode,
      'towards' => t.rcdFTowards,
      'ngoName' => t.rcdFNgoName,
      'ngoAddress' => t.rcdFNgoAddress,
      'ngoPan' => t.rcdFNgoPan,
      'ngoRegistrationNumber' => t.rcdFNgoReg,
      'ngo12a' => t.rcdFNgo12a,
      'tax80gText' => t.rcdF80g,
      'thankYou' => t.rcdFThankYou,
      'verifyCode' => t.rcdFVerifyCode,
      'staticText' => t.rcdFStaticText,
      'signatory1' => t.rcdFSignatory(1),
      'signatory2' => t.rcdFSignatory(2),
      'signatory3' => t.rcdFSignatory(3),
      'qrCode' => t.rcdFQr,
      'logo' => t.rcdFLogo,
      _ => key,
    };

const _imageKeys = {'signatory1', 'signatory2', 'signatory3', 'qrCode', 'logo'};
const _textKeys = ['receiptNumber', 'date', 'donorName', 'amount', 'amountInWords', 'mode', 'towards', 'donorPan', 'donorAddress',
  'tax80gText', 'ngoName', 'ngoAddress', 'ngoPan', 'ngoRegistrationNumber', 'ngo12a', 'thankYou', 'verifyCode', 'staticText'];

class TemplateEditorPage extends ConsumerStatefulWidget {
  const TemplateEditorPage({super.key, required this.templateId});
  final String templateId;
  @override
  ConsumerState<TemplateEditorPage> createState() => _TemplateEditorPageState();
}

class _TemplateEditorPageState extends ConsumerState<TemplateEditorPage> {
  Map<String, dynamic>? _tp;
  double _pageW = 595.28, _pageH = 419.53;
  List<Map<String, dynamic>> _fields = [];
  final List<List<Map<String, dynamic>>> _undo = [];
  int? _selected;
  double _zoom = 1;
  bool _snap = true;
  bool _dirty = false;
  bool _busy = false;

  List<Map<String, dynamic>> _copy(List<Map<String, dynamic>> l) => [for (final f in l) Map<String, dynamic>.from(f)];

  void _load(Map<String, dynamic> tp) {
    _tp = tp;
    final def = Map<String, dynamic>.from(tp['definition'] as Map);
    final page = Map<String, dynamic>.from(def['page'] as Map);
    _pageW = (page['width'] as num).toDouble();
    _pageH = (page['height'] as num).toDouble();
    _fields = [for (final f in (def['fields'] as List? ?? const [])) Map<String, dynamic>.from(f as Map)];
    _undo.clear();
    _dirty = false;
  }

  void _change(void Function() f) {
    _undo.add(_copy(_fields));
    if (_undo.length > 50) _undo.removeAt(0);
    setState(() {
      f();
      _dirty = true;
    });
  }

  double _num(Object? v, double d) => v is num ? v.toDouble() : d;
  double _snapTo(double v) => _snap ? (v / 2).round() * 2.0 : v;

  double _boxH(Map<String, dynamic> f) => _imageKeys.contains(f['key'])
      ? _num(f['height'], 40)
      : _num(f['fontSize'], 10) * 1.25 * _num(f['maxLines'], 1).clamp(1, 6);

  Map<String, dynamic> _definition() => {
        'page': {'width': _pageW, 'height': _pageH},
        'showLogo': false,
        'fields': _fields,
      };

  Future<bool> _save({bool quiet = false}) async {
    final t = L10n.of(context);
    final ngoId = ref.read(currentNgoIdProvider)!;
    setState(() => _busy = true);
    try {
      final r = await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/receipt-templates/${widget.templateId}',
          body: {'definition': _definition(), 'version': _tp!['version']});
      setState(() {
        _tp = r;
        _dirty = false;
      });
      ref.invalidate(templatesProvider(ngoId));
      if (!quiet && mounted) showSnack(context, t.rcdSaved);
      return true;
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
      return false;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _preview() async {
    if (_dirty && !await _save(quiet: true)) return;
    if (!mounted) return;
    final ngoId = ref.read(currentNgoIdProvider)!;
    await downloadAndOffer(context, ref, '/api/v1/ngos/$ngoId/receipt-templates/${widget.templateId}/preview',
        'receipt-design-preview.pdf', 'application/pdf');
  }

  Future<void> _publish() async {
    final t = L10n.of(context);
    if (_dirty && !await _save(quiet: true)) return;
    if (!mounted) return;
    final ngoId = ref.read(currentNgoIdProvider)!;
    final api = ref.read(apiProvider);
    try {
      final v = await api.getJson('/api/v1/ngos/$ngoId/receipt-templates/${widget.templateId}/validation');
      final errors = (v['errors'] as List? ?? const []).map((e) => '$e').toList();
      final warnings = (v['warnings'] as List? ?? const []).map((e) => '$e').toList();
      if (!mounted) return;
      if (errors.isNotEmpty) {
        await _showChecks(errors, warnings);
        return;
      }
      final ok = await showDialog<bool>(
        context: context,
        builder: (d) => AlertDialog(
          title: Text(t.rcdPublishTitle),
          content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(t.rcdPublishText),
            for (final w in warnings) Padding(padding: const EdgeInsets.only(top: 6), child: Text('• ${_checkLabel(t, w)}',
                style: const TextStyle(color: AppColors.warning))),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(d, false), child: Text(t.cancel)),
            FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(t.rcdPublish)),
          ],
        ),
      );
      if (ok != true) return;
      final r = await api.post('/api/v1/ngos/$ngoId/receipt-templates/${widget.templateId}/submit');
      setState(() => _tp = r);
      ref.invalidate(templatesProvider(ngoId));
      if (mounted) showSnack(context, t.rcdPublished);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  String _checkLabel(L10n t, String c) {
    final code = c.split(':').first.split(' ').first;
    final where = c.contains(':') ? c.substring(c.indexOf(':') + 1).trim() : '';
    final key = where.split(' ').last;
    final field = _imageKeys.contains(key) || _textKeys.contains(key) ? fieldLabel(t, key) : where;
    return switch (code) {
      'REQUIRED_FIELD_MISSING' => t.rcdChkRequired(field),
      'OUT_OF_BOUNDS' => t.rcdChkOutside(field),
      'QR_TOO_SMALL' => t.rcdChkQrSmall,
      'QR_MISSING' => t.rcdChkQrMissing,
      'SIGNATORY_MISSING' => t.rcdChkNoSignatory,
      'TAX80G_MISSING' => t.rcdChk80gMissing,
      'OVERLAPPING_FIELDS' => t.rcdChkOverlap(where),
      'DUPLICATE_FIELD' => t.rcdChkDuplicate(field),
      'FOOTER_OVERLAP' => t.rcdChkFooter(field),
      _ => c,
    };
  }

  Future<void> _showChecks(List<String> errors, List<String> warnings) {
    final t = L10n.of(context);
    return showDialog<void>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text(t.rcdChecks),
        content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          for (final e in errors) Padding(padding: const EdgeInsets.only(bottom: 6),
              child: Text('• ${_checkLabel(t, e)}', style: const TextStyle(color: AppColors.error))),
          for (final w in warnings) Padding(padding: const EdgeInsets.only(bottom: 6),
              child: Text('• ${_checkLabel(t, w)}', style: const TextStyle(color: AppColors.warning))),
        ])),
        actions: [FilledButton(onPressed: () => Navigator.pop(d), child: Text(t.rcdOk))],
      ),
    );
  }

  Future<void> _changeBackground() async {
    final t = L10n.of(context);
    if (_dirty && !await _save(quiet: true)) return;
    if (!mounted) return;
    final pic = await pickReceiptPicture(context, maxBytes: _maxBackgroundBytes);
    if (pic == null || !mounted) return;
    final ngoId = ref.read(currentNgoIdProvider)!;
    try {
      final r = await ref.read(apiProvider).upload('/api/v1/ngos/$ngoId/receipt-templates/${widget.templateId}/background',
          bytes: pic.$1, filename: pic.$2);
      setState(() => _load(r));
      ref.invalidate(templatesProvider(ngoId));
      if (mounted) showSnack(context, t.rcdBackgroundSaved);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  void _addField() async {
    final t = L10n.of(context);
    final present = _fields.map((f) => '${f['key']}').toSet();
    final options = [
      for (final k in _textKeys) if (k == 'staticText' || !present.contains(k)) k,
      for (final k in _imageKeys) if (!present.contains(k)) k,
    ];
    final key = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (c) => SafeArea(child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(c).height * 0.7),
        child: ListView(shrinkWrap: true, children: [
          ListTile(title: Text(t.rcdAddField, style: const TextStyle(fontWeight: FontWeight.w700))),
          for (final k in options)
            ListTile(
              leading: Icon(_imageKeys.contains(k) ? Icons.image_outlined : Icons.text_fields),
              title: Text(fieldLabel(t, k)),
              onTap: () => Navigator.pop(c, k),
            ),
        ]),
      )),
    );
    if (key == null) return;
    String? text;
    if (key == 'staticText') {
      text = await _askText(t.rcdFStaticText, '');
      if (text == null || text.isEmpty) return;
    }
    _change(() {
      final image = _imageKeys.contains(key);
      final w = image ? (key == 'qrCode' ? 62.0 : key == 'logo' ? 60.0 : 140.0) : 220.0;
      _fields.add({
        'key': key, 'x': (_pageW - w) / 2, 'y': _pageH / 2, 'width': w, 'fontSize': image ? 8 : 10, 'align': image ? 'CENTER' : 'LEFT',
        'bold': key == 'donorName' || key == 'amount', 'color': null, 'font': null, 'text': text, 'label': null, 'condition': null,
        'height': image ? (key == 'qrCode' ? 62.0 : key == 'logo' ? 60.0 : 70.0) : null, 'maxLines': image ? null : 1,
      });
      _selected = _fields.length - 1;
    });
  }

  Future<String?> _askText(String title, String initial) async {
    final c = TextEditingController(text: initial);
    final t = L10n.of(context);
    final r = await showDialog<String>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text(title),
        content: TextField(controller: c, maxLength: title == t.rcdFStaticText ? 300 : 60, autofocus: true),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d), child: Text(t.cancel)),
          FilledButton(onPressed: () => Navigator.pop(d, c.text.trim()), child: Text(t.rcdOk)),
        ],
      ),
    );
    c.dispose();
    return r;
  }

  void _move(int i, double dxPt, double dyPt) {
    final f = _fields[i];
    final w = _num(f['width'], 100), h = _boxH(f);
    f['x'] = (_num(f['x'], 0) + dxPt).clamp(0, _pageW - w).toDouble();
    f['y'] = (_num(f['y'], 0) + dyPt).clamp(0, _pageH - h).toDouble();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    if (_tp == null) {
      final list = ref.watch(templatesProvider(ngoId));
      return Scaffold(
        appBar: AppBar(title: Text(t.rcdEditorTitle)),
        body: list.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(templatesProvider(ngoId))),
          data: (items) {
            final tp = items.where((x) => x['id'] == widget.templateId).firstOrNull;
            if (tp == null) return EmptyState(icon: Icons.design_services_outlined, title: t.rcdNotFound);
            WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted && _tp == null) setState(() => _load(tp)); });
            return const LoadingView();
          },
        ),
      );
    }
    final bg = ref.watch(templateBackgroundProvider((ngoId, widget.templateId, (_tp!['currentVersion'] as num).toInt()))).value;
    final sample = _sample(t);
    final sel = _selected != null && _selected! < _fields.length ? _fields[_selected!] : null;
    return PopScope(
      canPop: !_dirty,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final leave = await confirm(context, title: t.rcdUnsavedTitle, message: t.rcdUnsavedText,
            confirmLabel: t.rcdDiscard, cancelLabel: t.cancel, destructive: true);
        if (leave && context.mounted) {
          setState(() => _dirty = false);
          Navigator.of(context).pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(t.rcdEditorTitle),
          actions: [
            IconButton(tooltip: t.rcdUndo, onPressed: _undo.isEmpty ? null : () => setState(() {
              _fields = _undo.removeLast();
              _selected = null;
              _dirty = true;
            }), icon: const Icon(Icons.undo)),
            IconButton(tooltip: t.rcdPreview, onPressed: _busy ? null : _preview, icon: const Icon(Icons.preview_outlined)),
            PopupMenuButton<String>(
              onSelected: (v) {
                switch (v) {
                  case 'bg':
                    _changeBackground();
                  case 'snap':
                    setState(() => _snap = !_snap);
                  case 'check':
                    () async {
                      if (_dirty && !await _save(quiet: true)) return;
                      final r = await ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/receipt-templates/${widget.templateId}/validation');
                      await _showChecks([for (final e in (r['errors'] as List? ?? const [])) '$e'],
                          [for (final w in (r['warnings'] as List? ?? const [])) '$w']);
                    }();
                }
              },
              itemBuilder: (_) => [
                PopupMenuItem(value: 'bg', child: Text(t.rcdChangeBackground)),
                CheckedPopupMenuItem(value: 'snap', checked: _snap, child: Text(t.rcdSnap)),
                PopupMenuItem(value: 'check', child: Text(t.rcdChecks)),
              ],
            ),
          ],
        ),
        body: Column(children: [
          // Zoom and hint bar.
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpacing.md, 4, AppSpacing.md, 4),
            child: Row(children: [
              Expanded(child: Text(sel == null ? t.rcdHintSelect : t.rcdHintDrag(fieldLabel(t, '${sel['key']}')),
                  style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
              SegmentedButton<double>(
                showSelectedIcon: false,
                style: const ButtonStyle(visualDensity: VisualDensity.compact),
                segments: const [
                  ButtonSegment(value: 1, label: Text('1×')),
                  ButtonSegment(value: 2, label: Text('2×')),
                  ButtonSegment(value: 3, label: Text('3×')),
                ],
                selected: {_zoom},
                onSelectionChanged: (s) => setState(() => _zoom = s.first),
              ),
            ]),
          ),
          Expanded(
            child: LayoutBuilder(builder: (context, c) {
              final canvasW = (c.maxWidth - 16) * _zoom;
              final px = canvasW / _pageW; // logical pixels per PDF point
              final canvasH = _pageH * px;
              final locked = sel != null; // while a field is selected, one-finger drags move it instead of scrolling
              final canvas = GestureDetector(
                onTap: () => setState(() => _selected = null),
                child: SizedBox(
                  width: canvasW,
                  height: canvasH,
                  child: Stack(children: [
                    Positioned.fill(child: bg == null
                        ? Container(color: Colors.white, alignment: Alignment.center, child: Text(t.rcdNoBackground))
                        : Image.memory(bg, fit: BoxFit.fill)),
                    // Where the server prints the small app credit line (TemplateDefinition.FOOTER_PT = 11 pt).
                    Positioned(
                      left: canvasW / 2 - 140 * px,
                      width: 280 * px,
                      bottom: 0,
                      height: 11 * px,
                      child: IgnorePointer(
                        child: Container(
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.7),
                            border: Border.all(color: AppColors.textMuted.withValues(alpha: 0.5), width: 0.5),
                          ),
                          child: FittedBox(child: Text(t.rcdFooterMark, style: const TextStyle(color: AppColors.textMuted))),
                        ),
                      ),
                    ),
                    for (var i = 0; i < _fields.length; i++) _fieldBox(i, px, sample),
                  ]),
                ),
              );
              return Container(
                color: const Color(0xFFE9ECEF),
                child: SingleChildScrollView(
                  physics: locked ? const NeverScrollableScrollPhysics() : null,
                  padding: const EdgeInsets.all(8),
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    physics: locked ? const NeverScrollableScrollPhysics() : null,
                    child: Material(elevation: 2, child: canvas),
                  ),
                ),
              );
            }),
          ),
          if (sel != null) _properties(t, sel) else _bottomBar(t),
        ]),
      ),
    );
  }

  Widget _fieldBox(int i, double px, Map<String, String> sample) {
    final t = L10n.of(context);
    final f = _fields[i];
    final key = '${f['key']}';
    final image = _imageKeys.contains(key);
    final selected = _selected == i;
    final w = _num(f['width'], 100) * px, h = _boxH(f) * px;
    final fs = _num(f['fontSize'], 10) * px;
    String text = key == 'staticText' ? '${f['text'] ?? ''}' : (sample[key] ?? key);
    if (!image && f['label'] != null && '${f['label']}'.isNotEmpty) text = '${f['label']} $text';
    final align = switch (f['align']) { 'CENTER' => TextAlign.center, 'RIGHT' => TextAlign.right, _ => TextAlign.left };
    return Positioned(
      left: _num(f['x'], 0) * px,
      top: _num(f['y'], 0) * px,
      width: w,
      height: h,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => setState(() => _selected = i),
        onPanStart: (_) {
          if (_selected != i) setState(() => _selected = i);
          _undo.add(_copy(_fields));
        },
        onPanUpdate: (d) => setState(() {
          _move(i, d.delta.dx / px, d.delta.dy / px);
          _dirty = true;
        }),
        onPanEnd: (_) => setState(() {
          f['x'] = _snapTo(_num(f['x'], 0)).clamp(0, _pageW - _num(f['width'], 100)).toDouble();
          f['y'] = _snapTo(_num(f['y'], 0)).clamp(0, _pageH - _boxH(f)).toDouble();
        }),
        child: Container(
          decoration: BoxDecoration(
            color: selected ? AppColors.primary.withValues(alpha: 0.12) : Colors.white.withValues(alpha: 0.35),
            border: Border.all(color: selected ? AppColors.primary : const Color(0xFF5B6478), width: selected ? 2 : 0.8),
          ),
          alignment: image ? Alignment.center : Alignment.topLeft,
          child: image
              ? FittedBox(child: Padding(
                  padding: const EdgeInsets.all(2),
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    Icon(key == 'qrCode' ? Icons.qr_code_2 : key == 'logo' ? Icons.image_outlined : Icons.draw_outlined,
                        color: const Color(0xFF14213D)),
                    Text(fieldLabel(t, key), style: const TextStyle(fontSize: 10, color: Color(0xFF14213D))),
                  ]),
                ))
              // The box is the field's height on paper; the preview text may be a little taller than the box on
              // screen (borders), so it is allowed to overflow vertically instead of being clipped.
              : OverflowBox(
                  alignment: Alignment.topLeft,
                  maxHeight: double.infinity,
                  child: Text(text, textAlign: align, maxLines: _num(f['maxLines'], 1).toInt().clamp(1, 6), overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: fs.clamp(4, 200).toDouble(), height: 1.2, color: const Color(0xFF14213D),
                          fontWeight: f['bold'] == true ? FontWeight.w700 : FontWeight.w400)),
                ),
        ),
      ),
    );
  }

  Widget _bottomBar(L10n t) => SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.sm),
          child: Row(children: [
            Expanded(child: OutlinedButton.icon(onPressed: _addField, icon: const Icon(Icons.add), label: Text(t.rcdAddField))),
            const SizedBox(width: 8),
            Expanded(child: OutlinedButton.icon(onPressed: _busy || !_dirty ? null : () => _save(),
                icon: const Icon(Icons.save_outlined), label: Text(t.save))),
            const SizedBox(width: 8),
            Expanded(child: FilledButton.icon(onPressed: _busy ? null : _publish, icon: const Icon(Icons.verified_outlined),
                label: Text(t.rcdPublish))),
          ]),
        ),
      );

  Widget _properties(L10n t, Map<String, dynamic> f) {
    final key = '${f['key']}';
    final image = _imageKeys.contains(key);
    Widget stepper(String label, double value, double step, double min, double max, void Function(double) set, {int digits = 0}) =>
        Row(mainAxisSize: MainAxisSize.min, children: [
          Text(label, style: const TextStyle(fontSize: 12, color: AppColors.textMuted)),
          IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.remove_circle_outline),
              onPressed: value - step < min ? null : () => _change(() => set(value - step))),
          Text(value.toStringAsFixed(digits), style: const TextStyle(fontWeight: FontWeight.w600)),
          IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.add_circle_outline),
              onPressed: value + step > max ? null : () => _change(() => set(value + step))),
        ]);
    void nudge(double dx, double dy) => _change(() => _move(_selected!, dx, dy));
    return Material(
      elevation: 8,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, AppSpacing.sm),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(children: [
              Expanded(child: Text(fieldLabel(t, key), style: const TextStyle(fontWeight: FontWeight.w700))),
              IconButton(tooltip: t.rcdRemoveField, icon: const Icon(Icons.delete_outline, color: AppColors.error),
                  onPressed: () => _change(() { _fields.removeAt(_selected!); _selected = null; })),
              IconButton(tooltip: t.rcdDone, icon: const Icon(Icons.check), onPressed: () => setState(() => _selected = null)),
            ]),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(children: [
                IconButton(icon: const Icon(Icons.arrow_left), onPressed: () => nudge(-1, 0)),
                IconButton(icon: const Icon(Icons.arrow_drop_up), onPressed: () => nudge(0, -1)),
                IconButton(icon: const Icon(Icons.arrow_drop_down), onPressed: () => nudge(0, 1)),
                IconButton(icon: const Icon(Icons.arrow_right), onPressed: () => nudge(1, 0)),
                const SizedBox(width: 8),
                stepper(t.rcdWidth, _num(f['width'], 100), 10, 20, _pageW, (v) => f['width'] = v),
                if (image) stepper(t.rcdHeight, _num(f['height'], 40), 5, 20, _pageH, (v) => f['height'] = v),
              ]),
            ),
            if (!image)
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(children: [
                  stepper(t.rcdSize, _num(f['fontSize'], 10), 0.5, 6, 48, (v) => f['fontSize'] = v, digits: 1),
                  stepper(t.rcdLines, _num(f['maxLines'], 1), 1, 1, 6, (v) => f['maxLines'] = v.toInt()),
                  FilterChip(label: const Text('B', style: TextStyle(fontWeight: FontWeight.w800)), selected: f['bold'] == true,
                      onSelected: (v) => _change(() => f['bold'] = v)),
                  const SizedBox(width: 8),
                  SegmentedButton<String>(
                    showSelectedIcon: false,
                    style: const ButtonStyle(visualDensity: VisualDensity.compact),
                    segments: const [
                      ButtonSegment(value: 'LEFT', icon: Icon(Icons.format_align_left)),
                      ButtonSegment(value: 'CENTER', icon: Icon(Icons.format_align_center)),
                      ButtonSegment(value: 'RIGHT', icon: Icon(Icons.format_align_right)),
                    ],
                    selected: {'${f['align'] ?? 'LEFT'}'},
                    onSelectionChanged: (s) => _change(() => f['align'] = s.first),
                  ),
                  const SizedBox(width: 8),
                  TextButton.icon(
                    onPressed: () async {
                      final isStatic = key == 'staticText';
                      final v = await _askText(isStatic ? t.rcdFStaticText : t.rcdLabel, '${(isStatic ? f['text'] : f['label']) ?? ''}');
                      if (v == null) return;
                      if (isStatic && v.isEmpty) return;
                      _change(() {
                        if (isStatic) {
                          f['text'] = v;
                        } else {
                          f['label'] = v.isEmpty ? null : v;
                        }
                      });
                    },
                    icon: const Icon(Icons.short_text),
                    label: Text(key == 'staticText' ? t.rcdEditText : t.rcdLabel),
                  ),
                ]),
              ),
          ]),
        ),
      ),
    );
  }
}
