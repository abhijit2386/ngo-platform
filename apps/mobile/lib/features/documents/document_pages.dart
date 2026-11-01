import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../campaigns/campaign_api.dart' show teamNamesProvider, parseDay, isoDay, today;
import 'documents_api.dart';

String _day(Object? iso) {
  final d = parseDay(iso);
  return d == null ? '—' : DateFormat('d MMM yyyy').format(d);
}

// ============================================================ vault home / search

class DocumentsPage extends ConsumerStatefulWidget {
  const DocumentsPage({super.key, this.initialCategory});
  final String? initialCategory;
  @override
  ConsumerState<DocumentsPage> createState() => _DocumentsPageState();
}

class _DocumentsPageState extends ConsumerState<DocumentsPage> {
  late DocQuery _q = DocQuery(category: widget.initialCategory);
  final _search = TextEditingController();

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final overview = ref.watch(docOverviewProvider(ngoId)).value;
    final counts = Map<String, dynamic>.from(overview?['categories'] as Map? ?? const {});
    final canUpload = overview?['canUpload'] == true;
    final data = ref.watch(docSearchProvider((ngoId, _q)));
    return Scaffold(
      appBar: AppBar(title: Text(t.docTitle), actions: [
        IconButton(tooltip: t.docRecycleBin, icon: const Icon(Icons.delete_outline), onPressed: () => context.push('/ngo/documents/bin')),
      ]),
      floatingActionButton: canUpload
          ? FloatingActionButton.extended(
              onPressed: () => context.push('/ngo/documents/upload'), icon: const Icon(Icons.upload_file), label: Text(t.docUpload))
          : null,
      body: RefreshIndicator(
        onRefresh: () async => invalidateDocuments(ref, ngoId),
        child: ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, 96), children: [
          TextField(
            controller: _search,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.search),
              hintText: t.docSearchHint,
              suffixIcon: _q.q.isEmpty ? null : IconButton(tooltip: t.accClear, icon: const Icon(Icons.close), onPressed: () {
                _search.clear();
                setState(() => _q = _q.copy(q: ''));
              }),
            ),
            onSubmitted: (v) => setState(() => _q = _q.copy(q: v.trim())),
          ),
          const SizedBox(height: AppSpacing.sm),
          if (overview != null) StorageBar(used: overview['usedBytes'] as num? ?? 0, limit: overview['limitBytes'] as num? ?? -1),
          const SizedBox(height: AppSpacing.md),
          SizedBox(
            height: 40,
            child: ListView(scrollDirection: Axis.horizontal, children: [
              Padding(
                padding: const EdgeInsets.only(right: 6),
                child: ChoiceChip(label: Text(t.accFilterAll), selected: _q.category == null, onSelected: (_) => setState(() => _q = _q.copy(category: null))),
              ),
              for (final c in documentCategories)
                if ((counts[c] as num? ?? 0) > 0 || _q.category == c)
                  Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: ChoiceChip(
                      avatar: Icon(categoryIcon(c), size: 18, color: categoryColor(c)),
                      label: Text('${categoryLabel(t, c)} (${counts[c] ?? 0})'),
                      selected: _q.category == c,
                      onSelected: (_) => setState(() => _q = _q.copy(category: _q.category == c ? null : c)),
                    ),
                  ),
            ]),
          ),
          const SizedBox(height: AppSpacing.xs),
          Wrap(spacing: 6, runSpacing: 0, children: [
            _FilterChip(
              icon: Icons.insert_drive_file_outlined,
              label: _q.type == null ? t.docAnyType : fileTypeLabel(t, _q.type),
              active: _q.type != null,
              onTap: () => _pickType(context),
            ),
            _FilterChip(
              icon: Icons.date_range_outlined,
              label: _q.from == null ? t.docAnyDate : '${_day(_q.from)} – ${_day(_q.to)}',
              active: _q.from != null,
              onTap: () => _pickDates(context),
            ),
            FilterChip(label: Text(t.docMine), selected: _q.mine, onSelected: (v) => setState(() => _q = _q.copy(mine: v))),
            if (_q.tag != null) InputChip(label: Text('#${_q.tag}'), onDeleted: () => setState(() => _q = _q.copy(tag: null))),
            PopupMenuButton<String>(
              tooltip: t.docSort,
              initialValue: _q.sort,
              onSelected: (v) => setState(() => _q = _q.copy(sort: v)),
              itemBuilder: (_) => [
                PopupMenuItem(value: 'new', child: Text(t.docSortNewest)),
                PopupMenuItem(value: 'date', child: Text(t.docSortDocDate)),
                PopupMenuItem(value: 'name', child: Text(t.docSortName)),
                PopupMenuItem(value: 'size', child: Text(t.docSortSize)),
              ],
              child: Chip(avatar: const Icon(Icons.sort, size: 18), label: Text(t.docSort)),
            ),
          ]),
          if ((overview?['tags'] as List? ?? const []).isNotEmpty && _q.tag == null)
            SizedBox(
              height: 40,
              child: ListView(scrollDirection: Axis.horizontal, children: [
                for (final tag in (overview!['tags'] as List).take(15))
                  Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: ActionChip(label: Text('#$tag'), visualDensity: VisualDensity.compact,
                        onPressed: () => setState(() => _q = _q.copy(tag: '$tag'))),
                  ),
              ]),
            ),
          const SizedBox(height: AppSpacing.sm),
          data.when(
            loading: () => const Padding(padding: EdgeInsets.all(AppSpacing.xl), child: Center(child: CircularProgressIndicator())),
            error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(docSearchProvider)),
            data: (p) {
              final items = (p['items'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
              if (items.isEmpty) {
                return EmptyState(
                  icon: Icons.folder_open_outlined,
                  title: _q.q.isEmpty && !_q.hasFilters ? t.docEmpty : t.docNoMatch,
                  message: _q.q.isEmpty && !_q.hasFilters ? t.docEmptyHint : null,
                );
              }
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(t.docCount(p['total'] as int? ?? items.length), style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                const SizedBox(height: AppSpacing.xs),
                for (final d in items) DocumentTile(ngoId: ngoId, doc: d),
              ]);
            },
          ),
        ]),
      ),
    );
  }

  Future<void> _pickType(BuildContext context) async {
    final t = L10n.of(context);
    final v = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (c) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(leading: const Icon(Icons.all_inclusive), title: Text(t.docAnyType), onTap: () => Navigator.pop(c, '')),
          for (final ft in documentFileTypes)
            ListTile(leading: Icon(fileTypeIcon(ft)), title: Text(fileTypeLabel(t, ft)), selected: _q.type == ft, onTap: () => Navigator.pop(c, ft)),
        ]),
      ),
    );
    if (v != null) setState(() => _q = _q.copy(type: v.isEmpty ? null : v));
  }

  Future<void> _pickDates(BuildContext context) async {
    if (_q.from != null) {
      setState(() => _q = _q.copy(from: null, to: null));
      return;
    }
    final r = await showDateRangePicker(context: context, firstDate: DateTime(2000), lastDate: today());
    if (r != null) setState(() => _q = _q.copy(from: isoDay(r.start), to: isoDay(r.end)));
  }
}

class _FilterChip extends StatelessWidget {
  const _FilterChip({required this.icon, required this.label, required this.active, required this.onTap});
  final IconData icon;
  final String label;
  final bool active;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => ActionChip(
        avatar: Icon(icon, size: 18, color: active ? AppColors.primary : null),
        label: Text(label),
        backgroundColor: active ? AppColors.primarySoft : null,
        onPressed: onTap,
      );
}

class DocumentTile extends ConsumerWidget {
  const DocumentTile({super.key, required this.ngoId, required this.doc, this.trailing});
  final String ngoId;
  final Map<String, dynamic> doc;
  final Widget? trailing;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final d = doc;
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final me = ref.watch(meProvider).value;
    final who = d['uploadedBy'] == null ? null : (d['uploadedBy'] == me?.userId ? t.you : names['${d['uploadedBy']}']);
    final sub = [
      categoryLabel(t, d['category'] as String?),
      _day(d['documentDate'] ?? d['createdAt']),
      sizeLabel(d['sizeBytes']),
      if (who != null) who,
    ].join(' · ');
    final tags = (d['tags'] as List? ?? const []).cast<Object>();
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        padding: const EdgeInsets.all(AppSpacing.md),
        onTap: () => context.push('/ngo/documents/${d['id']}'),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          CategoryBadge(d['category'] as String?, fileType: d['fileType'] as String?),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('${d['title']}', style: const TextStyle(fontWeight: FontWeight.w600), maxLines: 2, overflow: TextOverflow.ellipsis)),
              if (d['visibility'] == 'RESTRICTED')
                Tooltip(message: t.docRestricted, child: const Icon(Icons.lock_outline, size: 16, color: AppColors.textMuted)),
            ]),
            const SizedBox(height: 2),
            Text(sub, style: const TextStyle(color: AppColors.textMuted, fontSize: 13), maxLines: 2, overflow: TextOverflow.ellipsis),
            if (d['linkLabel'] != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Row(children: [
                  const Icon(Icons.link, size: 14, color: AppColors.textMuted),
                  const SizedBox(width: 4),
                  Expanded(child: Text('${d['linkLabel']}', style: const TextStyle(color: AppColors.textMuted, fontSize: 12), maxLines: 1,
                      overflow: TextOverflow.ellipsis)),
                ]),
              ),
            if (tags.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Wrap(spacing: 4, runSpacing: 4, children: [
                  for (final tag in tags.take(4))
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(color: AppColors.primarySoft, borderRadius: BorderRadius.circular(AppRadius.chip)),
                      child: Text('#$tag', style: const TextStyle(fontSize: 11, color: AppColors.primary)),
                    ),
                ]),
              ),
          ])),
          if (trailing != null) trailing!,
        ]),
      ),
    );
  }
}

// ============================================================ upload / edit details

/// Upload a document, optionally linked (e.g. from a meeting: linkType=MEETING, linkId, linkLabel, category=MEETING).
class DocumentUploadPage extends ConsumerStatefulWidget {
  const DocumentUploadPage({super.key, this.category, this.linkType, this.linkId, this.linkLabel, this.edit});
  final String? category;
  final String? linkType;
  final String? linkId;
  final String? linkLabel;
  /// Existing document: edit its details only.
  final Map<String, dynamic>? edit;
  @override
  ConsumerState<DocumentUploadPage> createState() => _DocumentUploadPageState();
}

class _DocumentUploadPageState extends ConsumerState<DocumentUploadPage> {
  final _form = GlobalKey<FormState>();
  late final _title = TextEditingController(text: widget.edit?['title'] as String? ?? '');
  late final _description = TextEditingController(text: widget.edit?['description'] as String? ?? '');
  final _tagInput = TextEditingController();
  late String? _category = widget.edit?['category'] as String? ?? widget.category;
  late List<String> _tags = [...(widget.edit?['tags'] as List? ?? const []).map((e) => '$e')];
  late DateTime? _date = parseDay(widget.edit?['documentDate']);
  late DateTime? _expires = parseDay(widget.edit?['expiresOn']);
  late String? _visibility = widget.edit?['visibility'] as String?;
  Uint8List? _bytes;
  String? _fileName;

  bool get _editing => widget.edit != null;

  @override
  void dispose() {
    _title.dispose();
    _description.dispose();
    _tagInput.dispose();
    super.dispose();
  }

  String get _effectiveVisibility => _visibility ?? (confidentialCategories.contains(_category) ? 'RESTRICTED' : 'TEAM');

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final suggestions = ((ref.watch(docOverviewProvider(ngoId)).value?['tags'] as List?) ?? const []).map((e) => '$e')
        .where((s) => !_tags.contains(s)).take(12).toList();
    final showExpiry = _category == 'TAX_COMPLIANCE' || _category == 'LEGAL_REGISTRATION' || _category == 'AGREEMENT' || _expires != null;
    return Scaffold(
      appBar: AppBar(title: Text(_editing ? t.docEditDetails : t.docUpload)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          if (!_editing) ...[
            if (_bytes == null) ...[
              Text(t.docChooseFile, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(t.docChooseFileHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
              const SizedBox(height: AppSpacing.sm),
              Row(children: [
                Expanded(child: _PickButton(icon: Icons.photo_camera_outlined, label: t.accTakePhoto, onTap: () => _pickImage(ImageSource.camera))),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: _PickButton(icon: Icons.photo_library_outlined, label: t.accFromGallery, onTap: () => _pickImage(ImageSource.gallery))),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: _PickButton(icon: Icons.folder_open_outlined, label: t.docFromFiles, onTap: _pickFile)),
              ]),
            ] else
              AppCard(
                child: Row(children: [
                  const Icon(Icons.insert_drive_file_outlined, color: AppColors.primary),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(_fileName ?? '', style: const TextStyle(fontWeight: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                    Text(sizeLabel(_bytes!.length), style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                  ])),
                  TextButton(onPressed: () => setState(() { _bytes = null; _fileName = null; }), child: Text(t.docChange)),
                ]),
              ),
            const SizedBox(height: AppSpacing.md),
          ],
          if (widget.linkLabel != null) ...[
            Row(children: [
              const Icon(Icons.link, size: 18, color: AppColors.textMuted),
              const SizedBox(width: 6),
              Expanded(child: Text(t.docLinkedTo(widget.linkLabel!), style: const TextStyle(color: AppColors.textMuted))),
            ]),
            const SizedBox(height: AppSpacing.md),
          ],
          TextFormField(
            controller: _title,
            decoration: InputDecoration(labelText: t.docName, hintText: t.docNameHint),
            validator: V(t).text(min: 2, max: 200),
          ),
          const SizedBox(height: AppSpacing.md),
          DropdownButtonFormField<String>(
            initialValue: _category,
            isExpanded: true,
            decoration: InputDecoration(labelText: t.accCategory),
            hint: Text(t.accChooseCategory),
            items: [
              for (final c in documentCategories)
                DropdownMenuItem(value: c, child: Row(children: [
                  Icon(categoryIcon(c), size: 20, color: categoryColor(c)),
                  const SizedBox(width: AppSpacing.sm),
                  Text(categoryLabel(t, c)),
                ])),
            ],
            validator: (v) => v == null ? t.required : null,
            onChanged: (v) => setState(() => _category = v),
          ),
          const SizedBox(height: AppSpacing.md),
          Row(children: [
            Expanded(child: _DateField(label: t.docDate, value: _date, onChanged: (d) => setState(() => _date = d), last: today())),
            if (showExpiry) ...[
              const SizedBox(width: AppSpacing.sm),
              Expanded(child: _DateField(label: t.docExpiresOn, value: _expires, onChanged: (d) => setState(() => _expires = d),
                  last: DateTime(2100))),
            ],
          ]),
          const SizedBox(height: AppSpacing.md),
          Text(t.docTags, style: const TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Wrap(spacing: 6, runSpacing: 4, children: [
            for (final tag in _tags) InputChip(label: Text('#$tag'), onDeleted: () => setState(() => _tags.remove(tag))),
          ]),
          TextField(
            controller: _tagInput,
            decoration: InputDecoration(hintText: t.docTagHint, prefixText: '#'),
            textInputAction: TextInputAction.done,
            onSubmitted: _addTag,
            onChanged: (v) {
              if (v.endsWith(',') || v.endsWith(' ') && v.trim().length > 12) _addTag(v);
            },
          ),
          if (suggestions.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Wrap(spacing: 6, children: [
                for (final s in suggestions) ActionChip(label: Text('#$s'), visualDensity: VisualDensity.compact, onPressed: () => _addTag(s)),
              ]),
            ),
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _description,
            maxLines: 3,
            maxLength: 1000,
            decoration: InputDecoration(labelText: t.docDescription),
            validator: V(t).text(max: 1000, optional: true),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(t.docWhoCanSee, style: const TextStyle(fontWeight: FontWeight.w600)),
          for (final v in ['TEAM', 'RESTRICTED'])
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(_effectiveVisibility == v ? Icons.radio_button_checked : Icons.radio_button_unchecked,
                  color: _effectiveVisibility == v ? AppColors.primary : AppColors.textMuted),
              title: Text(v == 'TEAM' ? t.docVisTeam : t.docVisRestricted),
              subtitle: Text(v == 'TEAM' ? t.docVisTeamHint : t.docVisRestrictedHint),
              onTap: () => setState(() => _visibility = v),
            ),
          const SizedBox(height: AppSpacing.md),
          BusyButton(label: _editing ? t.save : t.docUpload, icon: _editing ? Icons.check : Icons.cloud_upload_outlined, onPressed: () => _save(ngoId)),
        ]),
      ),
    );
  }

  void _addTag(String raw) {
    final s = raw.replaceAll(',', ' ').trim().replaceAll(RegExp(r'^#'), '').toLowerCase();
    if (s.isEmpty || _tags.contains(s) || _tags.length >= 10) {
      _tagInput.clear();
      return;
    }
    setState(() {
      _tags = [..._tags, s.length > 30 ? s.substring(0, 30) : s];
      _tagInput.clear();
    });
  }

  Future<void> _pickImage(ImageSource source) async {
    try {
      final f = await ImagePicker().pickImage(source: source, maxWidth: 2400, maxHeight: 2400, imageQuality: 85);
      if (f == null) return;
      final b = await f.readAsBytes();
      setState(() {
        _bytes = b;
        _fileName = f.name.isEmpty ? 'photo.jpg' : f.name;
        if (_title.text.trim().isEmpty) _title.text = '';
      });
    } catch (_) {
      if (mounted) showSnack(context, L10n.of(context).accCouldNotReadFile, error: true);
    }
  }

  Future<void> _pickFile() async {
    final f = await FilePicker.pickFile(type: FileType.custom,
        allowedExtensions: ['pdf', 'jpg', 'jpeg', 'png', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx']);
    if (f == null) return;
    final b = await f.readAsBytes();
    if (!mounted) return;
    if (b.length > 25 * 1024 * 1024) {
      showSnack(context, L10n.of(context).docTooLarge, error: true);
      return;
    }
    setState(() {
      _bytes = Uint8List.fromList(b);
      _fileName = f.name;
      if (_title.text.trim().isEmpty) {
        final n = f.name.contains('.') ? f.name.substring(0, f.name.lastIndexOf('.')) : f.name;
        _title.text = n.replaceAll(RegExp(r'[_-]+'), ' ').trim();
      }
    });
  }

  Future<void> _save(String ngoId) async {
    final t = L10n.of(context);
    if (_tagInput.text.trim().isNotEmpty) _addTag(_tagInput.text);
    if (!_editing && _bytes == null) {
      showSnack(context, t.docChooseFileFirst, error: true);
      return;
    }
    if (!_form.currentState!.validate()) return;
    final meta = <String, dynamic>{
      'title': _title.text.trim(),
      'category': _category,
      'tags': _tags,
      'visibility': _effectiveVisibility,
      'description': _description.text.trim().isEmpty ? null : _description.text.trim(),
      'documentDate': _date == null ? null : isoDay(_date!),
      'expiresOn': _expires == null ? null : isoDay(_expires!),
      if (widget.linkType != null) 'linkType': widget.linkType,
      if (widget.linkId != null) 'linkId': widget.linkId,
      if (widget.linkLabel != null) 'linkLabel': widget.linkLabel,
      if (_editing) 'version': widget.edit!['version'],
    };
    try {
      final api = ref.read(apiProvider);
      final Map<String, dynamic> saved = _editing
          ? await api.put('/api/v1/ngos/$ngoId/library/${widget.edit!['id']}', body: meta)
          : await uploadDocument(api, ngoId, _bytes!, _fileName ?? 'document', meta);
      invalidateDocuments(ref, ngoId, '${saved['id']}');
      if (!mounted) return;
      showSnack(context, _editing ? t.docSaved : t.docUploaded);
      if (_editing || widget.linkType != null) {
        context.pop(saved);
      } else {
        context.pushReplacement('/ngo/documents/${saved['id']}');
      }
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

class _PickButton extends StatelessWidget {
  const _PickButton({required this.icon, required this.label, required this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Container(
          height: 92,
          decoration: BoxDecoration(border: Border.all(color: AppColors.primary.withValues(alpha: 0.5)), borderRadius: BorderRadius.circular(12)),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(icon, color: AppColors.primary),
            const SizedBox(height: 6),
            Text(label, textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          ]),
        ),
      );
}

class _DateField extends StatelessWidget {
  const _DateField({required this.label, required this.value, required this.onChanged, required this.last});
  final String label;
  final DateTime? value;
  final ValueChanged<DateTime?> onChanged;
  final DateTime last;
  @override
  Widget build(BuildContext context) => InkWell(
        onTap: () async {
          final d = await showDatePicker(context: context, initialDate: value ?? (last.isBefore(today()) ? last : today()),
              firstDate: DateTime(1950), lastDate: last);
          if (d != null) onChanged(d);
        },
        child: InputDecorator(
          decoration: InputDecoration(
            labelText: label,
            suffixIcon: value == null
                ? const Icon(Icons.calendar_today_outlined)
                : IconButton(tooltip: L10n.of(context).accClear, icon: const Icon(Icons.close), onPressed: () => onChanged(null)),
          ),
          child: Text(value == null ? L10n.of(context).docOptional : DateFormat('d MMM yyyy').format(value!),
              style: TextStyle(color: value == null ? AppColors.textMuted : AppColors.text)),
        ),
      );
}

// ============================================================ detail

class DocumentDetailPage extends ConsumerWidget {
  const DocumentDetailPage({super.key, required this.documentId});
  final String documentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(documentProvider((ngoId, documentId)));
    return Scaffold(
      appBar: AppBar(title: Text(t.docDocument)),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message),
        data: (d) => _DocumentBody(ngoId: ngoId, d: d),
      ),
    );
  }
}

class _DocumentBody extends ConsumerWidget {
  const _DocumentBody({required this.ngoId, required this.d});
  final String ngoId;
  final Map<String, dynamic> d;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final id = '${d['id']}';
    final m = ref.watch(currentMembershipProvider);
    final me = ref.watch(meProvider).value;
    final admin = m?.isAdmin ?? false;
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    String who(Object? u) => u == null ? '—' : (u == me?.userId ? t.you : names['$u'] ?? t.aTeamMember);
    final deleted = d['status'] == 'DELETED';
    final canManage = d['canManage'] == true;
    final expires = parseDay(d['expiresOn']);
    final expired = expires != null && expires.isBefore(today());
    final soon = expires != null && !expired && expires.difference(today()).inDays <= 60;
    final tags = (d['tags'] as List? ?? const []).map((e) => '$e').toList();
    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/library/$id';

    Future<void> run(Future<void> Function() a, String done) async {
      try {
        await a();
        invalidateDocuments(ref, ngoId, id);
        if (context.mounted) showSnack(context, done);
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
      }
    }

    return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
      if (d['fileType'] == 'IMAGE')
        ClipRRect(
          borderRadius: BorderRadius.circular(AppRadius.card),
          child: AspectRatio(
            aspectRatio: 4 / 3,
            child: ColoredBox(
              color: AppColors.primarySoft,
              child: ref.watch(docBytesProvider((ngoId, id))).when(
                    loading: () => const Center(child: CircularProgressIndicator()),
                    error: (_, __) => const Center(child: Icon(Icons.broken_image_outlined)),
                    data: (b) => InkWell(onTap: () => openDocument(context, ref, ngoId, d), child: Image.memory(b, fit: BoxFit.contain)),
                  ),
            ),
          ),
        )
      else
        AppCard(
          onTap: () => openDocument(context, ref, ngoId, d),
          child: Row(children: [
            CategoryBadge(d['category'] as String?, fileType: d['fileType'] as String?, size: 56),
            const SizedBox(width: AppSpacing.md),
            Expanded(child: Text('${fileTypeLabel(t, d['fileType'] as String?)} · ${sizeLabel(d['sizeBytes'])}',
                style: const TextStyle(color: AppColors.textMuted))),
            const Icon(Icons.open_in_new, color: AppColors.primary),
          ]),
        ),
      const SizedBox(height: AppSpacing.md),
      Text('${d['title']}', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 4),
      Wrap(spacing: 6, runSpacing: 4, children: [
        Chip(avatar: Icon(categoryIcon(d['category'] as String?), size: 18, color: categoryColor(d['category'] as String?)),
            label: Text(categoryLabel(t, d['category'] as String?)), visualDensity: VisualDensity.compact),
        Chip(avatar: Icon(d['visibility'] == 'RESTRICTED' ? Icons.lock_outline : Icons.groups_outlined, size: 18),
            label: Text(d['visibility'] == 'RESTRICTED' ? t.docVisRestricted : t.docVisTeam), visualDensity: VisualDensity.compact),
        for (final tag in tags) Chip(label: Text('#$tag'), visualDensity: VisualDensity.compact),
      ]),
      if (deleted)
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.sm),
          child: AppCard(
            color: AppColors.error.withValues(alpha: 0.08),
            child: Text(t.docInBin(_day(d['purgeAfter'])), style: const TextStyle(color: AppColors.error)),
          ),
        ),
      if (expired || soon)
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.sm),
          child: AppCard(
            color: (expired ? AppColors.error : const Color(0xFFB45309)).withValues(alpha: 0.08),
            child: Row(children: [
              Icon(Icons.event_busy, color: expired ? AppColors.error : const Color(0xFFB45309)),
              const SizedBox(width: AppSpacing.sm),
              Expanded(child: Text(expired ? t.docExpired(_day(d['expiresOn'])) : t.docExpiresSoon(_day(d['expiresOn'])))),
            ]),
          ),
        ),
      const SizedBox(height: AppSpacing.md),
      if (!deleted)
        Row(children: [
          Expanded(child: FilledButton.icon(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () => openDocument(context, ref, ngoId, d),
            icon: const Icon(Icons.open_in_new),
            label: Text(t.accOpenFile),
          )),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: OutlinedButton.icon(
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () => shareDocument(context, ref, ngoId, d),
            icon: const Icon(Icons.share_outlined),
            label: Text(t.docShare),
          )),
        ]),
      const SizedBox(height: AppSpacing.md),
      AppCard(child: Column(children: [
        KeyValueRow(t.docUploadedBy, '${who(d['uploadedBy'])} · ${formatDate(d['createdAt'])}'),
        if (d['documentDate'] != null) KeyValueRow(t.docDate, _day(d['documentDate'])),
        if (d['expiresOn'] != null) KeyValueRow(t.docExpiresOn, _day(d['expiresOn'])),
        if (d['linkLabel'] != null) KeyValueRow(t.docLinked, '${d['linkLabel']}'),
        if (d['originalName'] != null) KeyValueRow(t.docOriginalName, '${d['originalName']}'),
        if (d['updatedBy'] != null) KeyValueRow(t.docUpdatedBy, '${who(d['updatedBy'])} · ${formatDate(d['updatedAt'])}'),
      ])),
      if (d['linkType'] != null && _linkRoute(d) != null)
        TextButton.icon(onPressed: () => context.push(_linkRoute(d)!), icon: const Icon(Icons.link), label: Text(t.docOpenLinked)),
      if ((d['description'] as String?)?.isNotEmpty ?? false) ...[
        const SizedBox(height: AppSpacing.md),
        Text('${d['description']}', style: const TextStyle(height: 1.4)),
      ],
      if (d['source'] != 'UPLOAD') ...[
        const SizedBox(height: AppSpacing.md),
        Row(children: [
          const Icon(Icons.info_outline, size: 18, color: AppColors.textMuted),
          const SizedBox(width: 6),
          Expanded(child: Text(t.docManagedElsewhere, style: const TextStyle(color: AppColors.textMuted, fontSize: 13))),
        ]),
      ],
      const SizedBox(height: AppSpacing.lg),
      if (canManage && !deleted) ...[
        OutlinedButton.icon(
          style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
          onPressed: () async {
            final r = await Navigator.of(context).push(MaterialPageRoute<Map<String, dynamic>>(builder: (_) => DocumentUploadPage(edit: d)));
            if (r != null) invalidateDocuments(ref, ngoId, id);
          },
          icon: const Icon(Icons.edit_outlined),
          label: Text(t.docEditDetails),
        ),
        TextButton.icon(
          style: TextButton.styleFrom(foregroundColor: AppColors.error, minimumSize: const Size.fromHeight(kMinTouchTarget)),
          icon: const Icon(Icons.delete_outline),
          label: Text(t.docDelete),
          onPressed: () async {
            if (!await confirm(context, title: t.docDelete, message: t.docDeleteConfirm, confirmLabel: t.docDelete, cancelLabel: t.cancel,
                destructive: true)) return;
            await run(() => api.delete(base), t.docMovedToBin);
          },
        ),
      ],
      if (canManage && deleted)
        BusyButton(label: t.docRestore, icon: Icons.restore, onPressed: () => run(() => api.post('$base/restore'), t.docRestored)),
      if (admin) _ActivityLog(ngoId: ngoId, id: id, who: who),
    ]);
  }

  static String? _linkRoute(Map<String, dynamic> d) => switch (d['linkType']) {
        'CAMPAIGN' => '/ngo/campaigns/${d['linkId']}',
        'EXPENSE' => d['linkParentId'] == null ? null : '/ngo/campaigns/${d['linkParentId']}/expenses/${d['linkId']}',
        'MEETING' => '/ngo/meetings/${d['linkId']}',
        'RECEIPT' => '/ngo/receipts/${d['linkId']}',
        'NGO' => '/ngo/profile',
        _ => null,
      };
}

class _ActivityLog extends ConsumerWidget {
  const _ActivityLog({required this.ngoId, required this.id, required this.who});
  final String ngoId;
  final String id;
  final String Function(Object?) who;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(docActivityProvider((ngoId, id))).value ?? const [];
    if (data.isEmpty) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SizedBox(height: AppSpacing.md),
      SectionHeader(t.docActivity),
      for (final a in data.take(30))
        ListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          leading: Icon(_icon('${a['action']}'), size: 20, color: AppColors.textMuted),
          title: Text('${who(a['userId'])} · ${_label(t, '${a['action']}')}'),
          trailing: Text(DateFormat('d MMM, h:mm a').format(DateTime.parse('${a['at']}').toLocal()),
              style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        ),
    ]);
  }

  static IconData _icon(String a) => switch (a) {
        'VIEWED' => Icons.visibility_outlined,
        'DOWNLOADED' || 'LINK_CREATED' => Icons.download_outlined,
        'UPDATED' => Icons.edit_outlined,
        'DELETED' => Icons.delete_outline,
        'RESTORED' => Icons.restore,
        _ => Icons.upload_outlined,
      };

  static String _label(L10n t, String a) => switch (a) {
        'UPLOADED' || 'REGISTERED' => t.docActUploaded,
        'VIEWED' => t.docActViewed,
        'LINK_CREATED' => t.docActLink,
        'DOWNLOADED' => t.docActDownloaded,
        'UPDATED' => t.docActUpdated,
        'DELETED' => t.docActDeleted,
        'RESTORED' => t.docActRestored,
        _ => a,
      };
}

// ============================================================ recycle bin

class DocumentBinPage extends ConsumerWidget {
  const DocumentBinPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(docBinProvider(ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.docRecycleBin)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(docBinProvider(ngoId)),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message)]),
          data: (items) => ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            Text(t.docBinHint, style: const TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: AppSpacing.md),
            if (items.isEmpty) EmptyState(icon: Icons.delete_outline, title: t.docBinEmpty),
            for (final d in items)
              DocumentTile(
                ngoId: ngoId,
                doc: d,
                trailing: IconButton(
                  tooltip: t.docRestore,
                  icon: const Icon(Icons.restore, color: AppColors.primary),
                  onPressed: () async {
                    try {
                      await ref.read(apiProvider).post('/api/v1/ngos/$ngoId/library/${d['id']}/restore');
                      invalidateDocuments(ref, ngoId, '${d['id']}');
                      if (context.mounted) showSnack(context, t.docRestored);
                    } catch (e) {
                      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                    }
                  },
                ),
              ),
          ]),
        ),
      ),
    );
  }
}
