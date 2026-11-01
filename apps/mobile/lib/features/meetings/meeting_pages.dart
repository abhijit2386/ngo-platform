import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../campaigns/campaign_api.dart' show teamNamesProvider, parseDay, isoDay, today;
import '../documents/document_pages.dart' show DocumentTile, DocumentUploadPage;
import '../documents/documents_api.dart';
import 'meetings_api.dart';

String _when(Object? iso) {
  final d = DateTime.tryParse('${iso ?? ''}');
  return d == null ? '—' : DateFormat('EEE, d MMM yyyy · h:mm a').format(d.toLocal());
}

String _day(Object? iso) {
  final d = parseDay(iso);
  return d == null ? '—' : DateFormat('d MMM yyyy').format(d);
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, this.color);
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(AppRadius.chip)),
        child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 12)),
      );
}

// ============================================================ list + AGM tracker

class MeetingsPage extends ConsumerStatefulWidget {
  const MeetingsPage({super.key});
  @override
  ConsumerState<MeetingsPage> createState() => _MeetingsPageState();
}

class _MeetingsPageState extends ConsumerState<MeetingsPage> {
  String _when = 'upcoming';

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final admin = ref.watch(currentMembershipProvider)?.isAdmin ?? false;
    final summary = ref.watch(meetingSummaryProvider(ngoId)).value;
    final data = ref.watch(meetingsProvider((ngoId, _when)));
    return Scaffold(
      appBar: AppBar(title: Text(t.meetTitle)),
      floatingActionButton: admin
          ? FloatingActionButton.extended(onPressed: () => context.push('/ngo/meetings/new'), icon: const Icon(Icons.event_available),
              label: Text(t.meetSchedule))
          : null,
      body: RefreshIndicator(
        onRefresh: () async => invalidateMeetings(ref, ngoId),
        child: ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, 96), children: [
          if (summary != null) _AgmCard(summary: summary),
          if (summary != null && ((summary['myOpenActions'] as num? ?? 0) > 0 || admin)) ...[
            const SizedBox(height: AppSpacing.sm),
            AppCard(
              onTap: () => context.push('/ngo/action-items'),
              child: Row(children: [
                const Icon(Icons.checklist, color: AppColors.primary),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: Text((summary['myOpenActions'] as num? ?? 0) > 0 ? t.meetMyActions((summary['myOpenActions'] as num).toInt()) : t.meetNoOpenActions,
                    style: const TextStyle(fontWeight: FontWeight.w600))),
                const Icon(Icons.chevron_right),
              ]),
            ),
          ],
          if (admin && (summary?['minutesToApprove'] as num? ?? 0) > 0) ...[
            const SizedBox(height: AppSpacing.sm),
            AppCard(
              color: const Color(0xFFB45309).withValues(alpha: 0.08),
              onTap: () => setState(() => _when = 'past'),
              child: Row(children: [
                const Icon(Icons.edit_note, color: Color(0xFFB45309)),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: Text(t.meetMinutesToApprove((summary!['minutesToApprove'] as num).toInt()))),
              ]),
            ),
          ],
          const SizedBox(height: AppSpacing.md),
          SegmentedButton<String>(
            segments: [
              ButtonSegment(value: 'upcoming', label: Text(t.meetUpcoming), icon: const Icon(Icons.upcoming_outlined)),
              ButtonSegment(value: 'past', label: Text(t.meetPast), icon: const Icon(Icons.history)),
            ],
            selected: {_when},
            onSelectionChanged: (s) => setState(() => _when = s.first),
          ),
          const SizedBox(height: AppSpacing.md),
          data.when(
            loading: () => const Padding(padding: EdgeInsets.all(AppSpacing.xl), child: Center(child: CircularProgressIndicator())),
            error: (e, _) => ErrorState(message: ApiError.from(e).message),
            data: (list) => list.isEmpty
                ? EmptyState(icon: Icons.event_note_outlined, title: _when == 'upcoming' ? t.meetNoneUpcoming : t.meetNonePast,
                    message: admin ? t.meetEmptyHintAdmin : null)
                : Column(children: [for (final m in list) MeetingTile(meeting: m)]),
          ),
        ]),
      ),
    );
  }
}

class _AgmCard extends StatelessWidget {
  const _AgmCard({required this.summary});
  final Map<String, dynamic> summary;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final agm = summary['agmThisYear'] as Map?;
    final last = summary['lastAgm'] as Map?;
    final due = parseDay(summary['agmDueBy']);
    final overdue = agm == null && due != null && today().isAfter(due);
    final (color, icon, line) = agm == null
        ? (overdue ? AppColors.error : const Color(0xFFB45309), Icons.event_busy, overdue ? t.meetAgmOverdue(_day(summary['agmDueBy'])) : t.meetAgmNotYet(_day(summary['agmDueBy'])))
        : agm['status'] == MeetingStatus.approved
            ? (AppColors.success, Icons.verified_outlined, t.meetAgmHeld(_day(agm['startsAt'])))
            : (const Color(0xFF1D4ED8), Icons.event_available, t.meetAgmPlanned(_when(agm['startsAt'])));
    return AppCard(
      color: color.withValues(alpha: 0.07),
      onTap: agm == null ? null : () => context.push('/ngo/meetings/${agm['id']}'),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, color: color),
        const SizedBox(width: AppSpacing.sm),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(t.meetAgmFor('${summary['financialYear']}'), style: TextStyle(fontWeight: FontWeight.w700, color: color)),
          const SizedBox(height: 2),
          Text(line),
          if (last != null) Text(t.meetLastAgm(_day(last['startsAt'])), style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
        ])),
      ]),
    );
  }
}

class MeetingTile extends StatelessWidget {
  const MeetingTile({super.key, required this.meeting});
  final Map<String, dynamic> meeting;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final m = meeting;
    final c = meetingTypeColor(m['type'] as String?);
    final start = DateTime.tryParse('${m['startsAt']}')?.toLocal();
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        onTap: () => context.push('/ngo/meetings/${m['id']}'),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(
            width: 56,
            padding: const EdgeInsets.symmetric(vertical: 6),
            decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(12)),
            child: Column(children: [
              Text(start == null ? '' : DateFormat('MMM').format(start).toUpperCase(), style: TextStyle(color: c, fontSize: 11, fontWeight: FontWeight.w700)),
              Text(start == null ? '' : '${start.day}', style: TextStyle(color: c, fontSize: 22, fontWeight: FontWeight.w800)),
              Text(meetingTypeShort(m['type'] as String?), style: TextStyle(color: c, fontSize: 10, fontWeight: FontWeight.w700)),
            ]),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${m['title']}', style: const TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text('${start == null ? '' : DateFormat('EEE, h:mm a').format(start)} · ${modeLabel(t, m['mode'] as String?)}'
                '${m['venue'] == null ? '' : ' · ${m['venue']}'}',
                style: const TextStyle(color: AppColors.textMuted, fontSize: 13), maxLines: 2, overflow: TextOverflow.ellipsis),
            const SizedBox(height: 6),
            Wrap(spacing: 6, children: [
              _Pill(meetingStatusLabel(t, m['status'] as String?), meetingStatusColor(m['status'] as String?)),
              if (m['visibility'] == 'RESTRICTED') _Pill(t.meetInviteesOnly, AppColors.textMuted),
            ]),
          ])),
        ]),
      ),
    );
  }
}

// ============================================================ schedule / edit

class MeetingFormPage extends ConsumerStatefulWidget {
  const MeetingFormPage({super.key, this.meetingId});
  final String? meetingId;
  @override
  ConsumerState<MeetingFormPage> createState() => _MeetingFormPageState();
}

class _Attendee {
  _Attendee({this.userId, required this.name, this.designation});
  final String? userId;
  final String name;
  String? designation;
}

class _MeetingFormPageState extends ConsumerState<MeetingFormPage> {
  final _form = GlobalKey<FormState>();
  final _title = TextEditingController();
  final _venue = TextEditingController();
  final _link = TextEditingController();
  final _agenda = TextEditingController();
  final _quorum = TextEditingController();
  String _type = 'BOARD';
  String _mode = 'IN_PERSON';
  String? _visibility;
  DateTime _date = today().add(const Duration(days: 7));
  TimeOfDay _time = const TimeOfDay(hour: 11, minute: 0);
  final List<_Attendee> _attendees = [];
  Map<String, dynamic>? _existing;
  bool _loaded = false;

  @override
  void dispose() {
    for (final c in [_title, _venue, _link, _agenda, _quorum]) {
      c.dispose();
    }
    super.dispose();
  }

  void _fill(Map<String, dynamic> d) {
    final m = Map<String, dynamic>.from(d['meeting'] as Map);
    _existing = m;
    _type = '${m['type']}';
    _mode = '${m['mode']}';
    _visibility = m['visibility'] as String?;
    _title.text = '${m['title']}';
    _venue.text = '${m['venue'] ?? ''}';
    _link.text = '${m['onlineLink'] ?? ''}';
    _agenda.text = '${m['agenda'] ?? ''}';
    _quorum.text = m['quorumRequired'] == null ? '' : '${m['quorumRequired']}';
    final s = DateTime.parse('${m['startsAt']}').toLocal();
    _date = DateTime(s.year, s.month, s.day);
    _time = TimeOfDay(hour: s.hour, minute: s.minute);
    _attendees
      ..clear()
      ..addAll([
        for (final a in (d['attendees'] as List).map((e) => Map<String, dynamic>.from(e as Map)))
          _Attendee(userId: a['userId'] as String?, name: '${a['name']}', designation: a['designation'] as String?),
      ]);
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    if (widget.meetingId != null && !_loaded) {
      return ref.watch(meetingProvider((ngoId, widget.meetingId!))).when(
            loading: () => const Scaffold(body: LoadingView()),
            error: (e, _) => Scaffold(appBar: AppBar(), body: ErrorState(message: ApiError.from(e).message)),
            data: (d) {
              WidgetsBinding.instance.addPostFrameCallback((_) => setState(() { _fill(d); _loaded = true; }));
              return const Scaffold(body: LoadingView());
            },
          );
    }
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final vis = _visibility ?? ({'AGM', 'EGM', 'BOARD'}.contains(_type) ? 'RESTRICTED' : 'TEAM');
    return Scaffold(
      appBar: AppBar(title: Text(widget.meetingId == null ? t.meetSchedule : t.meetEdit)),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          Text(t.meetType, style: const TextStyle(fontWeight: FontWeight.w600)),
          Wrap(spacing: 6, children: [
            for (final ty in meetingTypes)
              ChoiceChip(label: Text(meetingTypeLabel(t, ty)), selected: _type == ty, onSelected: (_) => setState(() {
                    _type = ty;
                    if (_title.text.trim().isEmpty || meetingTypes.any((x) => _title.text == _defaultTitle(t, x))) _title.text = _defaultTitle(t, ty);
                  })),
          ]),
          const SizedBox(height: AppSpacing.md),
          TextFormField(controller: _title, decoration: InputDecoration(labelText: t.meetName), validator: V(t).text(min: 2, max: 200)),
          const SizedBox(height: AppSpacing.md),
          Row(children: [
            Expanded(child: InkWell(
              onTap: () async {
                final d = await showDatePicker(context: context, initialDate: _date, firstDate: today().subtract(const Duration(days: 1000)),
                    lastDate: today().add(const Duration(days: 730)));
                if (d != null) setState(() => _date = d);
              },
              child: InputDecorator(decoration: InputDecoration(labelText: t.meetDate, suffixIcon: const Icon(Icons.calendar_today_outlined)),
                  child: Text(DateFormat('EEE, d MMM yyyy').format(_date))),
            )),
            const SizedBox(width: AppSpacing.sm),
            SizedBox(width: 130, child: InkWell(
              onTap: () async {
                final tm = await showTimePicker(context: context, initialTime: _time);
                if (tm != null) setState(() => _time = tm);
              },
              child: InputDecorator(decoration: InputDecoration(labelText: t.meetTime, suffixIcon: const Icon(Icons.schedule)),
                  child: Text(_time.format(context))),
            )),
          ]),
          const SizedBox(height: AppSpacing.md),
          SegmentedButton<String>(
            segments: [for (final m in meetingModes) ButtonSegment(value: m, label: Text(modeLabel(t, m)))],
            selected: {_mode},
            onSelectionChanged: (s) => setState(() => _mode = s.first),
          ),
          if (_mode != 'ONLINE') ...[
            const SizedBox(height: AppSpacing.sm),
            TextFormField(controller: _venue, decoration: InputDecoration(labelText: t.meetVenue), validator: V(t).text(max: 200, optional: true)),
          ],
          if (_mode != 'IN_PERSON') ...[
            const SizedBox(height: AppSpacing.sm),
            TextFormField(controller: _link, keyboardType: TextInputType.url, decoration: InputDecoration(labelText: t.meetLink),
                validator: V(t).text(max: 300, optional: true)),
          ],
          const SizedBox(height: AppSpacing.md),
          TextFormField(controller: _agenda, maxLines: 5, maxLength: 4000,
              decoration: InputDecoration(labelText: t.meetAgenda, hintText: t.meetAgendaHint, alignLabelWithHint: true),
              validator: V(t).text(max: 4000, optional: true)),
          TextFormField(controller: _quorum, keyboardType: TextInputType.number, inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: InputDecoration(labelText: t.meetQuorum, helperText: t.meetQuorumHint)),
          const SizedBox(height: AppSpacing.md),
          Row(children: [
            Expanded(child: Text(t.meetAttendees(_attendees.length), style: const TextStyle(fontWeight: FontWeight.w600))),
            TextButton.icon(onPressed: () => _addMembers(context, names), icon: const Icon(Icons.group_add_outlined), label: Text(t.meetAddTeam)),
            TextButton.icon(onPressed: () => _addGuest(context), icon: const Icon(Icons.person_add_alt), label: Text(t.meetAddGuest)),
          ]),
          for (final a in _attendees)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: CircleAvatar(child: Text(a.name.characters.first.toUpperCase())),
              title: Text(a.name),
              subtitle: Text([a.designation, a.userId == null ? t.meetGuest : null].whereType<String>().join(' · ')),
              trailing: IconButton(tooltip: t.accRemoveLine, icon: const Icon(Icons.close), onPressed: () => setState(() => _attendees.remove(a))),
              onTap: () => _editDesignation(context, a),
            ),
          const SizedBox(height: AppSpacing.sm),
          Text(t.docWhoCanSee, style: const TextStyle(fontWeight: FontWeight.w600)),
          for (final v in ['TEAM', 'RESTRICTED'])
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(vis == v ? Icons.radio_button_checked : Icons.radio_button_unchecked, color: vis == v ? AppColors.primary : AppColors.textMuted),
              title: Text(v == 'TEAM' ? t.docVisTeam : t.meetInviteesOnly),
              subtitle: Text(v == 'TEAM' ? t.meetVisTeamHint : t.meetVisRestrictedHint),
              onTap: () => setState(() => _visibility = v),
            ),
          const SizedBox(height: AppSpacing.md),
          BusyButton(label: widget.meetingId == null ? t.meetSchedule : t.save, icon: Icons.check, onPressed: () => _save(ngoId, vis)),
        ]),
      ),
    );
  }

  String _defaultTitle(L10n t, String type) => type == 'AGM' ? '${t.meetAgm} ${_date.year}' : meetingTypeLabel(t, type);

  Future<void> _addMembers(BuildContext context, Map<String, String> names) async {
    final t = L10n.of(context);
    final chosen = <String>{for (final a in _attendees) if (a.userId != null) a.userId!};
    final r = await showModalBottomSheet<Set<String>>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (c) => StatefulBuilder(builder: (c, set) => SafeArea(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              ListTile(title: Text(t.meetAddTeam, style: const TextStyle(fontWeight: FontWeight.w700))),
              for (final e in names.entries)
                CheckboxListTile(value: chosen.contains(e.key), title: Text(e.value), onChanged: (v) => set(() => v == true ? chosen.add(e.key) : chosen.remove(e.key))),
              Padding(
                padding: const EdgeInsets.all(AppSpacing.md),
                child: FilledButton(style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                    onPressed: () => Navigator.pop(c, chosen), child: Text(t.ok)),
              ),
            ]),
          )),
    );
    if (r == null) return;
    setState(() {
      _attendees.removeWhere((a) => a.userId != null && !r.contains(a.userId));
      for (final id in r) {
        if (!_attendees.any((a) => a.userId == id)) _attendees.add(_Attendee(userId: id, name: names[id] ?? id));
      }
    });
  }

  Future<void> _addGuest(BuildContext context) async {
    final t = L10n.of(context);
    final name = TextEditingController();
    final role = TextEditingController();
    final key = GlobalKey<FormState>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(t.meetAddGuest),
        content: Form(key: key, child: Column(mainAxisSize: MainAxisSize.min, children: [
          TextFormField(controller: name, autofocus: true, decoration: InputDecoration(labelText: t.meetGuestName), validator: V(t).text(min: 2, max: 120)),
          TextFormField(controller: role, decoration: InputDecoration(labelText: t.meetDesignation, hintText: t.meetDesignationHint),
              validator: V(t).text(max: 80, optional: true)),
        ])),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: Text(t.cancel)),
          FilledButton(onPressed: () { if (key.currentState!.validate()) Navigator.pop(c, true); }, child: Text(t.ok)),
        ],
      ),
    );
    if (ok == true) setState(() => _attendees.add(_Attendee(name: name.text.trim(), designation: role.text.trim().isEmpty ? null : role.text.trim())));
  }

  Future<void> _editDesignation(BuildContext context, _Attendee a) async {
    final t = L10n.of(context);
    final c = TextEditingController(text: a.designation ?? '');
    final r = await showDialog<String>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text(a.name),
        content: TextField(controller: c, autofocus: true, maxLength: 80, decoration: InputDecoration(labelText: t.meetDesignation, hintText: t.meetDesignationHint)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d), child: Text(t.cancel)),
          FilledButton(onPressed: () => Navigator.pop(d, c.text.trim()), child: Text(t.ok)),
        ],
      ),
    );
    if (r != null) setState(() => a.designation = r.isEmpty ? null : r);
  }

  Future<void> _save(String ngoId, String vis) async {
    final t = L10n.of(context);
    if (!_form.currentState!.validate()) return;
    final starts = DateTime(_date.year, _date.month, _date.day, _time.hour, _time.minute);
    final body = {
      'type': _type,
      'title': _title.text.trim(),
      'startsAt': starts.toUtc().toIso8601String(),
      'mode': _mode,
      'venue': _mode == 'ONLINE' || _venue.text.trim().isEmpty ? null : _venue.text.trim(),
      'onlineLink': _mode == 'IN_PERSON' || _link.text.trim().isEmpty ? null : _link.text.trim(),
      'agenda': _agenda.text.trim().isEmpty ? null : _agenda.text.trim(),
      'visibility': vis,
      'quorumRequired': int.tryParse(_quorum.text.trim()),
      'attendees': [for (final a in _attendees) {'userId': a.userId, 'name': a.name, 'designation': a.designation}],
      if (_existing != null) 'version': _existing!['version'],
    };
    try {
      final api = ref.read(apiProvider);
      final r = widget.meetingId == null
          ? await api.post('/api/v1/ngos/$ngoId/meetings', body: body)
          : await api.put('/api/v1/ngos/$ngoId/meetings/${widget.meetingId}', body: body);
      final id = '${(r['meeting'] as Map)['id']}';
      invalidateMeetings(ref, ngoId, id);
      if (!mounted) return;
      showSnack(context, widget.meetingId == null ? t.meetScheduled : t.docSaved);
      if (widget.meetingId == null) {
        context.pushReplacement('/ngo/meetings/$id');
      } else {
        context.pop();
      }
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

// ============================================================ detail: attendance, minutes, resolutions, actions

class MeetingDetailPage extends ConsumerWidget {
  const MeetingDetailPage({super.key, required this.meetingId});
  final String meetingId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(meetingProvider((ngoId, meetingId)));
    return Scaffold(
      appBar: AppBar(title: Text(t.meetMeeting)),
      body: RefreshIndicator(
        onRefresh: () async => invalidateMeetings(ref, ngoId, meetingId),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message)]),
          data: (d) => _MeetingBody(ngoId: ngoId, d: d),
        ),
      ),
    );
  }
}

class _MeetingBody extends ConsumerWidget {
  const _MeetingBody({required this.ngoId, required this.d});
  final String ngoId;
  final Map<String, dynamic> d;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final m = Map<String, dynamic>.from(d['meeting'] as Map);
    final id = '${m['id']}';
    final status = '${m['status']}';
    final membership = ref.watch(currentMembershipProvider);
    final me = ref.watch(meProvider).value;
    final admin = membership?.isAdmin ?? false;
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final attendees = (d['attendees'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final resolutions = (d['resolutions'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final actions = (d['actions'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final started = DateTime.tryParse('${m['startsAt']}')?.isBefore(DateTime.now()) ?? false;
    final open = status == MeetingStatus.scheduled || status == MeetingStatus.draft;
    final canRecord = admin && open && started;
    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/meetings/$id';
    final docs = ref.watch(docSearchProvider((ngoId, DocQuery(linkType: 'MEETING', linkId: id)))).value;
    final docItems = ((docs?['items'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final canUpload = membership?.can('DOCUMENT_UPLOAD') ?? false;

    Future<void> run(Future<void> Function() a, String done) async {
      try {
        await a();
        invalidateMeetings(ref, ngoId, id);
        if (context.mounted) showSnack(context, done);
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
      }
    }

    final c = meetingTypeColor(m['type'] as String?);
    return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
      Row(children: [
        _Pill(meetingTypeLabel(t, m['type'] as String?), c),
        const SizedBox(width: 6),
        _Pill(meetingStatusLabel(t, status), meetingStatusColor(status)),
        if (m['visibility'] == 'RESTRICTED') ...[const SizedBox(width: 6), _Pill(t.meetInviteesOnly, AppColors.textMuted)],
      ]),
      const SizedBox(height: AppSpacing.sm),
      Text('${m['title']}', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 4),
      Row(children: [
        const Icon(Icons.schedule, size: 18, color: AppColors.textMuted),
        const SizedBox(width: 6),
        Expanded(child: Text(_when(m['startsAt']))),
      ]),
      if (m['venue'] != null || m['onlineLink'] != null)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Row(children: [
            Icon(m['mode'] == 'ONLINE' ? Icons.videocam_outlined : Icons.place_outlined, size: 18, color: AppColors.textMuted),
            const SizedBox(width: 6),
            Expanded(child: Text([m['venue'], m['onlineLink']].whereType<String>().join(' · '))),
            if (m['onlineLink'] != null)
              IconButton(tooltip: t.meetCopyLink, icon: const Icon(Icons.copy, size: 18), onPressed: () async {
                await Clipboard.setData(ClipboardData(text: '${m['onlineLink']}'));
                if (context.mounted) showSnack(context, t.meetLinkCopied);
              }),
          ]),
        ),
      if (status == MeetingStatus.cancelled)
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.sm),
          child: AppCard(color: AppColors.error.withValues(alpha: 0.08), child: Text(t.meetCancelledBecause('${m['cancelReason']}'))),
        ),
      if (status == MeetingStatus.approved && m['minutesDocumentId'] != null) ...[
        const SizedBox(height: AppSpacing.md),
        FilledButton.icon(
          style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
          onPressed: () => context.push('/ngo/documents/${m['minutesDocumentId']}'),
          icon: const Icon(Icons.picture_as_pdf_outlined),
          label: Text(t.meetMinutesPdf),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(t.meetApprovedBy(names['${m['approvedBy']}'] ?? t.aTeamMember, _day(m['approvedAt'])),
              style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
        ),
      ],
      if ((m['agenda'] as String?)?.isNotEmpty ?? false) ...[
        const SizedBox(height: AppSpacing.md),
        SectionHeader(t.meetAgenda),
        AppCard(child: Text('${m['agenda']}', style: const TextStyle(height: 1.5))),
      ],
      const SizedBox(height: AppSpacing.md),
      SectionHeader(t.meetAttendance(d['presentCount'] as int? ?? 0, attendees.length)),
      if (m['quorumRequired'] != null && status != MeetingStatus.scheduled)
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm),
          child: Text(d['quorumMet'] == true ? t.meetQuorumMet('${m['quorumRequired']}') : t.meetQuorumNotMet('${m['quorumRequired']}'),
              style: TextStyle(color: d['quorumMet'] == true ? AppColors.success : AppColors.error, fontWeight: FontWeight.w600)),
        ),
      AppCard(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.xs),
        child: Column(children: [
          if (attendees.isEmpty) Padding(padding: const EdgeInsets.all(AppSpacing.sm), child: Text(t.meetNoAttendees)),
          for (final a in attendees)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: Icon(
                a['present'] == true ? Icons.check_circle : (a['present'] == false ? Icons.cancel_outlined : Icons.radio_button_unchecked),
                color: a['present'] == true ? AppColors.success : AppColors.textMuted,
              ),
              title: Text('${a['name']}${a['userId'] != null && a['userId'] == me?.userId ? ' (${t.you})' : ''}'),
              subtitle: a['designation'] == null && a['userId'] != null ? null
                  : Text([a['designation'], a['userId'] == null ? t.meetGuest : null].whereType<String>().join(' · ')),
            ),
        ]),
      ),
      const SizedBox(height: AppSpacing.md),
      SectionHeader(t.meetMinutes),
      if ((m['minutes'] as String?)?.isNotEmpty ?? false)
        AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (m['chairperson'] != null) Text(t.meetChairedBy('${m['chairperson']}'), style: const TextStyle(fontWeight: FontWeight.w600)),
          if (m['chairperson'] != null) const SizedBox(height: 6),
          Text('${m['minutes']}', style: const TextStyle(height: 1.5)),
        ]))
      else
        Text(started ? t.meetNoMinutesYet : t.meetMinutesAfter, style: const TextStyle(color: AppColors.textMuted)),
      if (canRecord) ...[
        const SizedBox(height: AppSpacing.sm),
        OutlinedButton.icon(
          style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
          onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => MinutesEditorPage(ngoId: ngoId, detail: d))),
          icon: const Icon(Icons.edit_note),
          label: Text(status == MeetingStatus.draft ? t.meetEditMinutes : t.meetRecordMinutes),
        ),
      ],
      const SizedBox(height: AppSpacing.md),
      SectionHeader(t.meetResolutions, action: canRecord ? TextButton.icon(onPressed: () async {
        final r = await showModalBottomSheet<Map<String, dynamic>>(context: context, isScrollControlled: true, showDragHandle: true,
            builder: (_) => const _ResolutionSheet());
        if (r != null) await run(() => api.post('$base/resolutions', body: r), t.meetResolutionAdded);
      }, icon: const Icon(Icons.add), label: Text(t.meetAdd)) : null),
      if (resolutions.isEmpty) Text(t.meetNoResolutions, style: const TextStyle(color: AppColors.textMuted)),
      for (final r in resolutions)
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm),
          child: AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(r['number'] == null ? t.meetResolutionDraft : '${r['number']}', style: const TextStyle(fontWeight: FontWeight.w700))),
              _Pill(resultLabel(t, r['result'] as String?), r['result'] == 'PASSED' ? AppColors.success : (r['result'] == 'REJECTED' ? AppColors.error : AppColors.textMuted)),
              if (canRecord)
                IconButton(tooltip: t.accRemoveLine, icon: const Icon(Icons.delete_outline, size: 20),
                    onPressed: () => run(() => api.delete('$base/resolutions/${r['id']}'), t.meetRemoved)),
            ]),
            const SizedBox(height: 4),
            Text(t.meetResolvedThat('${r['text']}')),
            if (r['proposedBy'] != null || r['votesFor'] != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text([
                  if (r['proposedBy'] != null) t.meetProposedBy('${r['proposedBy']}'),
                  if (r['secondedBy'] != null) t.meetSecondedBy('${r['secondedBy']}'),
                  if (r['votesFor'] != null) t.meetVotes('${r['votesFor']}', '${r['votesAgainst'] ?? 0}'),
                ].join(' · '), style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
              ),
          ])),
        ),
      const SizedBox(height: AppSpacing.md),
      SectionHeader(t.meetActions, action: admin && open ? TextButton.icon(onPressed: () async {
        final r = await showModalBottomSheet<Map<String, dynamic>>(context: context, isScrollControlled: true, showDragHandle: true,
            builder: (_) => _ActionSheet(names: names));
        if (r != null) await run(() => api.post('$base/actions', body: r), t.meetActionAdded);
      }, icon: const Icon(Icons.add), label: Text(t.meetAdd)) : null),
      if (actions.isEmpty) Text(t.meetNoActions, style: const TextStyle(color: AppColors.textMuted)),
      for (final a in actions)
        ActionItemTile(
          ngoId: ngoId,
          action: a,
          canToggle: admin || a['ownerUserId'] == me?.userId,
          onRemove: admin && open ? () => run(() => api.delete('$base/actions/${a['id']}'), t.meetRemoved) : null,
          onChanged: () => invalidateMeetings(ref, ngoId, id),
        ),
      const SizedBox(height: AppSpacing.md),
      SectionHeader(t.meetDocuments, action: canUpload ? TextButton.icon(
        onPressed: () async {
          await Navigator.of(context).push(MaterialPageRoute<Map<String, dynamic>>(builder: (_) => DocumentUploadPage(
              category: 'MEETING', linkType: 'MEETING', linkId: id, linkLabel: '${m['title']}')));
          invalidateDocuments(ref, ngoId);
        },
        icon: const Icon(Icons.attach_file), label: Text(t.meetAttach)) : null),
      if (docItems.isEmpty) Text(t.meetNoDocuments, style: const TextStyle(color: AppColors.textMuted)),
      for (final doc in docItems) DocumentTile(ngoId: ngoId, doc: doc),
      const SizedBox(height: AppSpacing.lg),
      if (admin && status == MeetingStatus.draft)
        BusyButton(label: t.meetApprove, icon: Icons.verified_outlined, onPressed: () async {
          if (!await confirm(context, title: t.meetApprove, message: t.meetApproveConfirm, confirmLabel: t.meetApprove, cancelLabel: t.cancel)) return;
          await run(() => api.post('$base/approve', body: {'version': m['version']}), t.meetApproved);
        }),
      if (admin && open)
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.sm),
          child: OutlinedButton.icon(
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () => context.push('/ngo/meetings/$id/edit'),
            icon: const Icon(Icons.edit_outlined),
            label: Text(t.meetEdit),
          ),
        ),
      if (admin && status == MeetingStatus.scheduled)
        TextButton.icon(
          style: TextButton.styleFrom(foregroundColor: AppColors.error, minimumSize: const Size.fromHeight(kMinTouchTarget)),
          icon: const Icon(Icons.event_busy),
          label: Text(t.meetCancel),
          onPressed: () async {
            final reason = await _askText(context, t.meetCancel, t.meetCancelHint);
            if (reason == null) return;
            await run(() => api.post('$base/cancel', body: {'reason': reason}), t.meetCancelledDone);
          },
        ),
    ]);
  }
}

Future<String?> _askText(BuildContext context, String title, String hint) {
  final t = L10n.of(context);
  final c = TextEditingController();
  final key = GlobalKey<FormState>();
  return showDialog<String>(
    context: context,
    builder: (d) => AlertDialog(
      title: Text(title),
      content: Form(key: key, child: TextFormField(controller: c, autofocus: true, maxLength: 200, decoration: InputDecoration(hintText: hint),
          validator: V(t).text(min: 2, max: 200))),
      actions: [
        TextButton(onPressed: () => Navigator.pop(d), child: Text(t.cancel)),
        FilledButton(onPressed: () { if (key.currentState!.validate()) Navigator.pop(d, c.text.trim()); }, child: Text(t.ok)),
      ],
    ),
  );
}

class ActionItemTile extends ConsumerWidget {
  const ActionItemTile({super.key, required this.ngoId, required this.action, required this.canToggle, this.onRemove, this.onChanged,
      this.meetingTitle});
  final String ngoId;
  final Map<String, dynamic> action;
  final bool canToggle;
  final VoidCallback? onRemove;
  final VoidCallback? onChanged;
  final String? meetingTitle;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final a = action;
    final done = a['status'] == 'DONE';
    final due = parseDay(a['dueDate']);
    final late = !done && due != null && due.isBefore(today());
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        padding: const EdgeInsets.fromLTRB(AppSpacing.xs, AppSpacing.sm, AppSpacing.sm, AppSpacing.sm),
        child: Row(children: [
          Checkbox(
            value: done,
            onChanged: !canToggle ? null : (v) async {
              try {
                await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/action-items/${a['id']}', body: {'done': v == true});
                onChanged?.call();
              } catch (e) {
                if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
              }
            },
          ),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${a['text']}', style: TextStyle(fontWeight: FontWeight.w600, decoration: done ? TextDecoration.lineThrough : null)),
            Text([
              if (meetingTitle != null) meetingTitle!,
              if (a['ownerName'] != null) '${a['ownerName']}',
              if (due != null) t.meetDue(_day(a['dueDate'])),
            ].join(' · '), style: TextStyle(color: late ? AppColors.error : AppColors.textMuted, fontSize: 13)),
          ])),
          if (onRemove != null) IconButton(tooltip: t.accRemoveLine, icon: const Icon(Icons.delete_outline, size: 20), onPressed: onRemove),
        ]),
      ),
    );
  }
}

// ============================================================ minutes editor

class MinutesEditorPage extends ConsumerStatefulWidget {
  const MinutesEditorPage({super.key, required this.ngoId, required this.detail});
  final String ngoId;
  final Map<String, dynamic> detail;
  @override
  ConsumerState<MinutesEditorPage> createState() => _MinutesEditorPageState();
}

class _MinutesEditorPageState extends ConsumerState<MinutesEditorPage> {
  late final Map<String, dynamic> _m = Map<String, dynamic>.from(widget.detail['meeting'] as Map);
  late final _minutes = TextEditingController(text: _m['minutes'] as String? ?? '');
  late final _chair = TextEditingController(text: _m['chairperson'] as String? ?? '');
  late final Map<String, bool> _present = {
    for (final a in (widget.detail['attendees'] as List).map((e) => Map<String, dynamic>.from(e as Map))) '${a['id']}': a['present'] == true,
  };

  @override
  void dispose() {
    _minutes.dispose();
    _chair.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final attendees = (widget.detail['attendees'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final present = _present.values.where((v) => v).length;
    return Scaffold(
      appBar: AppBar(title: Text(t.meetRecordMinutes)),
      body: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
        Text('${_m['title']}', style: Theme.of(context).textTheme.titleMedium),
        Text(_when(_m['startsAt']), style: const TextStyle(color: AppColors.textMuted)),
        const SizedBox(height: AppSpacing.md),
        Text(t.meetWhoWasPresent(present, attendees.length), style: const TextStyle(fontWeight: FontWeight.w600)),
        if (_m['quorumRequired'] != null)
          Text(present >= (_m['quorumRequired'] as num) ? t.meetQuorumMet('${_m['quorumRequired']}') : t.meetQuorumNotMet('${_m['quorumRequired']}'),
              style: TextStyle(color: present >= (_m['quorumRequired'] as num) ? AppColors.success : AppColors.error, fontSize: 13)),
        for (final a in attendees)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            value: _present['${a['id']}'] ?? false,
            title: Text('${a['name']}'),
            subtitle: a['designation'] == null ? null : Text('${a['designation']}'),
            onChanged: (v) => setState(() => _present['${a['id']}'] = v == true),
          ),
        const SizedBox(height: AppSpacing.md),
        TextField(controller: _chair, decoration: InputDecoration(labelText: t.meetChairperson)),
        const SizedBox(height: AppSpacing.md),
        TextField(
          controller: _minutes,
          maxLines: 14,
          minLines: 8,
          maxLength: 20000,
          decoration: InputDecoration(labelText: t.meetMinutes, hintText: t.meetMinutesHint, alignLabelWithHint: true),
        ),
        const SizedBox(height: AppSpacing.md),
        BusyButton(label: t.meetSaveMinutes, icon: Icons.save_outlined, onPressed: () async {
          try {
            await ref.read(apiProvider).post('/api/v1/ngos/${widget.ngoId}/meetings/${_m['id']}/minutes', body: {
              'minutes': _minutes.text.trim(),
              'chairperson': _chair.text.trim().isEmpty ? null : _chair.text.trim(),
              'attendance': _present,
              'version': _m['version'],
            });
            invalidateMeetings(ref, widget.ngoId, '${_m['id']}');
            if (!context.mounted) return;
            showSnack(context, t.meetMinutesSaved);
            Navigator.pop(context);
          } catch (e) {
            if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
          }
        }),
        const SizedBox(height: AppSpacing.sm),
        Text(t.meetMinutesNote, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
      ]),
    );
  }
}

class _ResolutionSheet extends StatefulWidget {
  const _ResolutionSheet();
  @override
  State<_ResolutionSheet> createState() => _ResolutionSheetState();
}

class _ResolutionSheetState extends State<_ResolutionSheet> {
  final _form = GlobalKey<FormState>();
  final _text = TextEditingController();
  final _proposed = TextEditingController();
  final _seconded = TextEditingController();
  final _for = TextEditingController();
  final _against = TextEditingController();
  String _result = 'PASSED';

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Form(
        key: _form,
        child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(AppSpacing.md), children: [
          Text(t.meetAddResolution, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: AppSpacing.md),
          TextFormField(controller: _text, maxLines: 4, maxLength: 2000,
              decoration: InputDecoration(labelText: t.meetResolvedThatLabel, hintText: t.meetResolutionHint, alignLabelWithHint: true),
              validator: V(t).text(min: 5, max: 2000)),
          Row(children: [
            Expanded(child: TextFormField(controller: _proposed, decoration: InputDecoration(labelText: t.meetProposer))),
            const SizedBox(width: AppSpacing.sm),
            Expanded(child: TextFormField(controller: _seconded, decoration: InputDecoration(labelText: t.meetSeconder))),
          ]),
          Row(children: [
            Expanded(child: TextFormField(controller: _for, keyboardType: TextInputType.number, inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(labelText: t.meetVotesFor))),
            const SizedBox(width: AppSpacing.sm),
            Expanded(child: TextFormField(controller: _against, keyboardType: TextInputType.number, inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(labelText: t.meetVotesAgainst))),
          ]),
          const SizedBox(height: AppSpacing.md),
          SegmentedButton<String>(
            segments: [for (final r in ['PASSED', 'REJECTED', 'DEFERRED']) ButtonSegment(value: r, label: Text(resultLabel(t, r)))],
            selected: {_result},
            onSelectionChanged: (s) => setState(() => _result = s.first),
          ),
          const SizedBox(height: AppSpacing.md),
          FilledButton(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () {
              if (!_form.currentState!.validate()) return;
              String? opt(TextEditingController c) => c.text.trim().isEmpty ? null : c.text.trim();
              Navigator.pop(context, {
                'text': _text.text.trim(), 'proposedBy': opt(_proposed), 'secondedBy': opt(_seconded),
                'votesFor': int.tryParse(_for.text), 'votesAgainst': int.tryParse(_against.text), 'result': _result,
              });
            },
            child: Text(t.meetAdd),
          ),
        ]),
      ),
    );
  }
}

class _ActionSheet extends StatefulWidget {
  const _ActionSheet({required this.names});
  final Map<String, String> names;
  @override
  State<_ActionSheet> createState() => _ActionSheetState();
}

class _ActionSheetState extends State<_ActionSheet> {
  final _form = GlobalKey<FormState>();
  final _text = TextEditingController();
  String? _owner;
  DateTime? _due;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Form(
        key: _form,
        child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(AppSpacing.md), children: [
          Text(t.meetAddAction, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: AppSpacing.md),
          TextFormField(controller: _text, maxLines: 2, maxLength: 500, decoration: InputDecoration(labelText: t.meetActionText, hintText: t.meetActionHint),
              validator: V(t).text(min: 3, max: 500)),
          DropdownButtonFormField<String>(
            initialValue: _owner,
            isExpanded: true,
            decoration: InputDecoration(labelText: t.meetOwner),
            items: [for (final e in widget.names.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
            onChanged: (v) => setState(() => _owner = v),
          ),
          const SizedBox(height: AppSpacing.md),
          InkWell(
            onTap: () async {
              final d = await showDatePicker(context: context, initialDate: today().add(const Duration(days: 7)), firstDate: today(),
                  lastDate: today().add(const Duration(days: 730)));
              if (d != null) setState(() => _due = d);
            },
            child: InputDecorator(decoration: InputDecoration(labelText: t.meetDueDate, suffixIcon: const Icon(Icons.event)),
                child: Text(_due == null ? t.docOptional : DateFormat('d MMM yyyy').format(_due!))),
          ),
          const SizedBox(height: AppSpacing.md),
          FilledButton(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () {
              if (!_form.currentState!.validate()) return;
              Navigator.pop(context, {'text': _text.text.trim(), 'ownerUserId': _owner, 'dueDate': _due == null ? null : isoDay(_due!)});
            },
            child: Text(t.meetAdd),
          ),
        ]),
      ),
    );
  }
}

// ============================================================ my action items

class ActionItemsPage extends ConsumerStatefulWidget {
  const ActionItemsPage({super.key});
  @override
  ConsumerState<ActionItemsPage> createState() => _ActionItemsPageState();
}

class _ActionItemsPageState extends ConsumerState<ActionItemsPage> {
  bool _mine = true;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final admin = ref.watch(currentMembershipProvider)?.isAdmin ?? false;
    final me = ref.watch(meProvider).value;
    final data = ref.watch(actionItemsProvider((ngoId, _mine || !admin)));
    return Scaffold(
      appBar: AppBar(title: Text(t.meetActions)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(actionItemsProvider),
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          if (admin)
            SegmentedButton<bool>(
              segments: [ButtonSegment(value: true, label: Text(t.meetMine)), ButtonSegment(value: false, label: Text(t.meetEveryone))],
              selected: {_mine},
              onSelectionChanged: (s) => setState(() => _mine = s.first),
            ),
          const SizedBox(height: AppSpacing.md),
          data.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => ErrorState(message: ApiError.from(e).message),
            data: (list) => list.isEmpty
                ? EmptyState(icon: Icons.task_alt, title: t.meetNoActions)
                : Column(children: [
                    for (final r in list)
                      InkWell(
                        onTap: () => context.push('/ngo/meetings/${(r['action'] as Map)['meetingId']}'),
                        child: ActionItemTile(
                          ngoId: ngoId,
                          action: Map<String, dynamic>.from(r['action'] as Map),
                          meetingTitle: '${r['meetingTitle']}',
                          canToggle: admin || (r['action'] as Map)['ownerUserId'] == me?.userId,
                          onChanged: () => invalidateMeetings(ref, ngoId),
                        ),
                      ),
                  ]),
          ),
        ]),
      ),
    );
  }
}
