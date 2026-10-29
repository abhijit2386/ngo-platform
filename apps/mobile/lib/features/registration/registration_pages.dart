import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/util/labels.dart';
import '../../core/session/preferences.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../notifications/notification_pages.dart';
import 'registration_api.dart';

String causeLabel(String c) => c.split('_').map((w) => w[0] + w.substring(1).toLowerCase()).join(' ');

/// Entry for NGO users without an active membership: invitations, drafts, or start registration.
class NgoStartPage extends ConsumerWidget {
  const NgoStartPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final me = ref.watch(meProvider).value;
    final regs = ref.watch(myRegistrationsProvider);
    final invites = ref.watch(myInvitationsProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(t.hello(me?.name ?? '')),
        actions: [const NotificationBell(), IconButton(tooltip: t.settings, icon: const Icon(Icons.settings_outlined), onPressed: () => context.push('/settings'))],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(myRegistrationsProvider);
          ref.invalidate(myInvitationsProvider);
          ref.invalidate(meProvider);
        },
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          ...invites.when(
            data: (list) => list.isEmpty
                ? <Widget>[]
                : [
                    SectionHeader(t.pendingInvitations),
                    for (final i in list)
                      Padding(
                        padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                        child: AppCard(
                          child: Row(children: [
                            const Icon(Icons.mail_outline, color: AppColors.primary),
                            const SizedBox(width: AppSpacing.md),
                            Expanded(child: Text(t.roleName('${i['role']}'))),
                            SizedBox(
                              width: 120,
                              child: BusyButton(
                                label: t.accept,
                                onPressed: () async {
                                  try {
                                    await ref.read(apiProvider).post('/api/v1/me/invitations/${i['id']}/accept');
                                    await ref.read(prefsProvider.notifier).selectNgo('${i['ngoId']}');
                                    ref.invalidate(meProvider);
                                  } catch (e) {
                                    if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                                  }
                                },
                              ),
                            ),
                          ]),
                        ),
                      ),
                  ],
            loading: () => [],
            error: (_, __) => [],
          ),
          ...regs.when(
            data: (list) => [
              if (list.isNotEmpty) SectionHeader(t.myRegistrations),
              for (final n in list) _RegistrationCard(n),
              // Someone who already has a submitted registration gets a quiet link, not the full hero CTA.
              if (list.isNotEmpty && list.every((n) => n['registrationStatus'] != 'DRAFT'))
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: () => context.push('/ngo/register'),
                    icon: const Icon(Icons.add_business_outlined),
                    label: Text(t.registerNgo),
                  ),
                ),
              if (list.isEmpty) ...[
                const SizedBox(height: AppSpacing.md),
                AppCard(
                  color: AppColors.primarySoft,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    const Icon(Icons.apartment, color: AppColors.primary, size: 36),
                    const SizedBox(height: AppSpacing.sm),
                    Text(t.registerNgo, style: Theme.of(context).textTheme.titleLarge),
                    const SizedBox(height: AppSpacing.sm),
                    Text(t.registerNgoSub),
                    const SizedBox(height: AppSpacing.md),
                    FilledButton(onPressed: () => context.push('/ngo/register'), child: Text(t.getStarted)),
                  ]),
                ),
              ],
            ],
            loading: () => [const Padding(padding: EdgeInsets.all(48), child: LoadingView())],
            error: (e, _) => [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(myRegistrationsProvider))],
          ),
          const SizedBox(height: AppSpacing.lg),
          TextButton.icon(
            onPressed: () => ref.read(prefsProvider.notifier).setMode('donor'),
            icon: const Icon(Icons.volunteer_activism),
            label: Text(t.switchToDonor),
          ),
        ]),
      ),
    );
  }
}

class _RegistrationCard extends ConsumerWidget {
  const _RegistrationCard(this.n);
  final Map<String, dynamic> n;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final v = '${n['verificationStatus']}';
    final draft = n['registrationStatus'] == 'DRAFT';
    final (title, body) = switch (v) {
      'PENDING' => (t.verificationPending, t.verificationPendingBody),
      'INFO_REQUESTED' => (t.verificationInfoRequested, ''),
      'REJECTED' => (t.verificationRejected, ''),
      _ => (n['displayName'] as String, ''),
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        onTap: draft || v == 'INFO_REQUESTED' || v == 'REJECTED'
            ? () => context.push('/ngo/register/${n['id']}')
            : v == 'APPROVED'
                ? () async {
                    await ref.read(prefsProvider.notifier).selectNgo('${n['id']}');
                    if (context.mounted) context.go('/ngo/home');
                  }
                : null,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('${n['displayName']}', style: Theme.of(context).textTheme.titleMedium)),
            StatusBadge(draft ? 'DRAFT' : v, tone: StatusBadge.toneFor(draft ? 'DRAFT' : v)),
          ]),
          if (!draft) ...[const SizedBox(height: 8), Text(title, style: const TextStyle(fontWeight: FontWeight.w600))],
          if (body.isNotEmpty) ...[const SizedBox(height: 4), Text(body, style: const TextStyle(color: AppColors.textMuted))],
          if (draft || v == 'INFO_REQUESTED' || v == 'REJECTED') ...[
            const SizedBox(height: AppSpacing.sm),
            Align(alignment: Alignment.centerRight, child: Text(t.continueRegistration, style: const TextStyle(color: AppColors.primary, fontWeight: FontWeight.w600))),
          ],
        ]),
      ),
    );
  }
}

/// NGO-001..014: six-step wizard; every step is saved server-side, so drafts survive app restarts.
class RegistrationWizardPage extends ConsumerStatefulWidget {
  const RegistrationWizardPage({super.key, this.ngoId});
  final String? ngoId;
  @override
  ConsumerState<RegistrationWizardPage> createState() => _WizardState();
}

class _WizardState extends ConsumerState<RegistrationWizardPage> {
  String? _ngoId;
  int _step = 0;
  Map<String, dynamic>? _ngo;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _ngoId = widget.ngoId;
    if (_ngoId != null) _reload();
  }

  Future<void> _reload() async {
    setState(() => _loading = true);
    try {
      final n = await ref.read(apiProvider).getJson('/api/v1/ngos/$_ngoId');
      setState(() => _ngo = n);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _next(Map<String, dynamic>? ngo) {
    setState(() {
      if (ngo != null) {
        _ngo = ngo;
        _ngoId = ngo['id'] as String;
      }
      _step = (_step + 1).clamp(0, 5);
    });
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final steps = [t.orgDetails, t.registrationDetails, t.address, t.people, t.documents, t.review];
    final ref0 = ref.watch(referenceDataProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(t.registerNgo),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: t.back,
          onPressed: () => _step == 0 ? context.pop() : setState(() => _step--),
        ),
      ),
      body: _loading
          ? const LoadingView()
          : ref0.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(referenceDataProvider)),
              data: (refData) => Column(children: [
                _StepHeader(steps: steps, current: _step, onTap: _ngoId == null ? null : (i) => setState(() => _step = i)),
                const Divider(height: 1),
                Expanded(
                  child: switch (_step) {
                    0 => _OrganizationStep(ngo: _ngo, causes: List<String>.from(refData['causes'] as List), onSaved: _next),
                    1 => _RegistrationStep(ngo: _ngo!, states: List<String>.from(refData['states'] as List), onSaved: _next),
                    2 => _AddressStep(ngo: _ngo!, states: List<String>.from(refData['states'] as List), onSaved: _next),
                    3 => _PeopleStep(ngo: _ngo!, onSaved: _next),
                    4 => _DocumentsStep(ngoId: _ngoId!, ngo: _ngo!, onDone: () async {
                        await _reload();
                        setState(() => _step = 5);
                      }),
                    _ => _ReviewStep(ngo: _ngo!, onEdit: (i) => setState(() => _step = i), onReload: _reload),
                  },
                ),
              ]),
            ),
    );
  }
}

class _StepHeader extends StatefulWidget {
  const _StepHeader({required this.steps, required this.current, this.onTap});
  final List<String> steps;
  final int current;
  final void Function(int)? onTap;

  @override
  State<_StepHeader> createState() => _StepHeaderState();
}

class _StepHeaderState extends State<_StepHeader> {
  late final List<GlobalKey> _keys = List.generate(widget.steps.length, (_) => GlobalKey());

  @override
  void initState() {
    super.initState();
    _reveal();
  }

  @override
  void didUpdateWidget(covariant _StepHeader old) {
    super.didUpdateWidget(old);
    if (old.current != widget.current) _reveal();
  }

  /// Keep the active step chip on screen as the wizard advances.
  void _reveal() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final ctx = _keys[widget.current].currentContext;
      if (ctx != null && ctx.mounted) {
        Scrollable.ensureVisible(ctx, alignment: 0.5, duration: const Duration(milliseconds: 250), curve: Curves.easeOut);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final steps = widget.steps;
    final current = widget.current;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Semantics(
          label: '${current + 1} / ${steps.length}: ${steps[current]}',
          child: LinearProgressIndicator(value: (current + 1) / steps.length, minHeight: 6, borderRadius: BorderRadius.circular(6)),
        ),
        const SizedBox(height: 8),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(children: [
            for (var i = 0; i < steps.length; i++)
              Padding(
                key: _keys[i],
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  label: Text('${i + 1}. ${steps[i]}'),
                  selected: i == current,
                  onSelected: widget.onTap == null ? null : (_) => widget.onTap!(i),
                ),
              ),
          ]),
        ),
      ]),
    );
  }
}

/// Shared scaffolding for a form step: scrollable fields + sticky save button + server field errors.
class _FormStep extends StatelessWidget {
  const _FormStep({required this.formKey, required this.children, required this.onSave, required this.label});
  final GlobalKey<FormState> formKey;
  final List<Widget> children;
  final Future<void> Function() onSave;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Form(
      key: formKey,
      autovalidateMode: AutovalidateMode.onUserInteraction,
      child: Column(children: [
        Expanded(child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: children)),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.md),
            child: BusyButton(
              label: label,
              onPressed: () async {
                if (!(formKey.currentState?.validate() ?? false)) return; // focuses first invalid via Form
                await onSave();
              },
            ),
          ),
        ),
      ]),
    );
  }
}

Widget _gap() => const SizedBox(height: AppSpacing.md);

class _OrganizationStep extends ConsumerStatefulWidget {
  const _OrganizationStep({required this.ngo, required this.causes, required this.onSaved});
  final Map<String, dynamic>? ngo;
  final List<String> causes;
  final void Function(Map<String, dynamic>) onSaved;
  @override
  ConsumerState<_OrganizationStep> createState() => _OrganizationStepState();
}

class _OrganizationStepState extends ConsumerState<_OrganizationStep> {
  final _form = GlobalKey<FormState>();
  late final _legal = TextEditingController(text: widget.ngo?['legalName'] as String?);
  late final _display = TextEditingController(text: widget.ngo?['displayName'] as String?);
  late final _desc = TextEditingController(text: widget.ngo?['description'] as String?);
  late final _email = TextEditingController(text: widget.ngo?['officialEmail'] as String?);
  late final _phone = TextEditingController(text: widget.ngo?['officialPhone'] as String?);
  late final _web = TextEditingController(text: widget.ngo?['website'] as String?);
  late String _type = (widget.ngo?['registrationType'] as String?) ?? 'PUBLIC_TRUST';
  late final Set<String> _causes = {...(widget.ngo?['areasOfActivity'] as List? ?? const []).map((e) => '$e')};
  Map<String, String> _serverErrors = {};

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final v = V(t);
    return _FormStep(
      formKey: _form,
      label: t.continueLabel,
      onSave: () async {
        final body = {
          'legalName': _legal.text.trim(),
          'displayName': _display.text.trim(),
          'registrationType': _type,
          'description': _desc.text.trim(),
          'areasOfActivity': _causes.toList(),
          'officialEmail': _email.text.trim(),
          'officialPhone': _phone.text.trim(),
          'website': _web.text.trim(),
        };
        try {
          final api = ref.read(apiProvider);
          final ngo = widget.ngo == null
              ? await api.post('/api/v1/ngos', body: {'organization': body})
              : await api.put('/api/v1/ngos/${widget.ngo!['id']}/organization', body: body);
          ref.invalidate(myRegistrationsProvider);
          widget.onSaved(ngo);
        } catch (e) {
          final err = ApiError.from(e);
          setState(() => _serverErrors = err.fieldErrors);
          if (mounted) showSnack(context, err.message, error: true);
        }
      },
      children: [
        TextFormField(controller: _legal, decoration: InputDecoration(labelText: t.legalName, errorText: _serverErrors['legalName']),
            textCapitalization: TextCapitalization.words, validator: v.text(min: 3, max: 200, pattern: V.name)),
        _gap(),
        TextFormField(controller: _display, decoration: InputDecoration(labelText: t.displayName, errorText: _serverErrors['displayName']),
            textCapitalization: TextCapitalization.words, validator: v.text(min: 2, max: 120, pattern: V.name)),
        _gap(),
        DropdownButtonFormField<String>(
          initialValue: _type,
          decoration: InputDecoration(labelText: t.registrationType),
          items: [
            DropdownMenuItem(value: 'PUBLIC_TRUST', child: Text(t.typePublicTrust)),
            DropdownMenuItem(value: 'SOCIETY', child: Text(t.typeSociety)),
            DropdownMenuItem(value: 'SECTION_8_COMPANY', child: Text(t.typeSection8)),
            DropdownMenuItem(value: 'OTHER', child: Text(t.typeOther)),
          ],
          onChanged: (x) => setState(() => _type = x ?? _type),
        ),
        _gap(),
        TextFormField(controller: _desc, maxLines: 4, maxLength: 2000,
            decoration: InputDecoration(labelText: t.description, alignLabelWithHint: true, errorText: _serverErrors['description']),
            validator: v.text(min: 30, max: 2000)),
        Text(t.areasOfActivity, style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 4, children: [
          for (final c in widget.causes)
            FilterChip(
              label: Text(causeLabel(c)),
              selected: _causes.contains(c),
              onSelected: (s) => setState(() => s ? (_causes.length < 8 ? _causes.add(c) : null) : _causes.remove(c)),
            ),
        ]),
        _gap(),
        TextFormField(controller: _email, keyboardType: TextInputType.emailAddress,
            decoration: InputDecoration(labelText: t.officialEmail, errorText: _serverErrors['officialEmail']), validator: v.emailField()),
        _gap(),
        TextFormField(controller: _phone, keyboardType: TextInputType.phone, maxLength: 10,
            decoration: InputDecoration(labelText: t.officialPhone, prefixText: '+91 ', errorText: _serverErrors['officialPhone']),
            validator: v.phoneField()),
        TextFormField(controller: _web, keyboardType: TextInputType.url,
            decoration: InputDecoration(labelText: t.website, errorText: _serverErrors['website']),
            validator: v.text(optional: true, max: 200, pattern: RegExp(r'^https?://\S+\.\S+'))),
      ],
    );
  }
}

class _RegistrationStep extends ConsumerStatefulWidget {
  const _RegistrationStep({required this.ngo, required this.states, required this.onSaved});
  final Map<String, dynamic> ngo;
  final List<String> states;
  final void Function(Map<String, dynamic>) onSaved;
  @override
  ConsumerState<_RegistrationStep> createState() => _RegistrationStepState();
}

class _RegistrationStepState extends ConsumerState<_RegistrationStep> {
  final _form = GlobalKey<FormState>();
  late final _number = TextEditingController(text: widget.ngo['registrationNumber'] as String?);
  late final _darpan = TextEditingController(text: widget.ngo['darpanId'] as String?);
  late final _pan = TextEditingController(text: widget.ngo['pan'] as String?);
  late final _80g = TextEditingController(text: widget.ngo['tax80gNumber'] as String?);
  late DateTime? _date = DateTime.tryParse('${widget.ngo['registrationDate']}');
  late String? _state = widget.ngo['registrationState'] as String? ?? 'Maharashtra';
  Map<String, String> _serverErrors = {};

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final v = V(t);
    return _FormStep(
      formKey: _form,
      label: t.continueLabel,
      onSave: () async {
        if (_date == null) {
          showSnack(context, '${t.registrationDate}: ${t.required}', error: true);
          return;
        }
        try {
          final ngo = await ref.read(apiProvider).put('/api/v1/ngos/${widget.ngo['id']}/registration', body: {
            'registrationNumber': _number.text.trim(),
            'registrationDate': _date!.toIso8601String().substring(0, 10),
            'registrationState': _state,
            'darpanId': _darpan.text.trim().toUpperCase(),
            'pan': _pan.text.trim().toUpperCase(),
            'tax80gNumber': _80g.text.trim(),
          });
          widget.onSaved(ngo);
        } catch (e) {
          final err = ApiError.from(e);
          setState(() => _serverErrors = err.fieldErrors);
          if (mounted) showSnack(context, err.message, error: true);
        }
      },
      children: [
        TextFormField(controller: _number, decoration: InputDecoration(labelText: t.registrationNumber, errorText: _serverErrors['registrationNumber']),
            validator: v.text(min: 3, max: 60, pattern: RegExp(r'^[A-Za-z0-9/\-. ]+$'))),
        _gap(),
        InkWell(
          onTap: () async {
            final d = await showDatePicker(context: context, firstDate: DateTime(1860), lastDate: DateTime.now(),
                initialDate: _date ?? DateTime(2015));
            if (d != null) setState(() => _date = d);
          },
          child: InputDecorator(
            decoration: InputDecoration(labelText: t.registrationDate, suffixIcon: const Icon(Icons.calendar_month)),
            child: Text(_date == null ? '—' : formatDate(_date!.toIso8601String())),
          ),
        ),
        _gap(),
        DropdownButtonFormField<String>(
          initialValue: widget.states.contains(_state) ? _state : null,
          isExpanded: true,
          decoration: InputDecoration(labelText: t.registrationState),
          items: [for (final s in widget.states) DropdownMenuItem(value: s, child: Text(s))],
          onChanged: (s) => setState(() => _state = s),
          validator: (s) => s == null ? t.required : null,
        ),
        _gap(),
        TextFormField(controller: _darpan, textCapitalization: TextCapitalization.characters,
            decoration: InputDecoration(labelText: t.darpanId, helperText: t.darpanNote, helperMaxLines: 2, errorText: _serverErrors['darpanId']),
            validator: v.darpanField),
        _gap(),
        TextFormField(controller: _pan, textCapitalization: TextCapitalization.characters, maxLength: 10,
            decoration: InputDecoration(labelText: t.pan, errorText: _serverErrors['pan']), validator: v.panField),
        TextFormField(controller: _80g, decoration: InputDecoration(labelText: t.tax80g), validator: v.text(optional: true, max: 60)),
      ],
    );
  }
}

class _AddressStep extends ConsumerStatefulWidget {
  const _AddressStep({required this.ngo, required this.states, required this.onSaved});
  final Map<String, dynamic> ngo;
  final List<String> states;
  final void Function(Map<String, dynamic>) onSaved;
  @override
  ConsumerState<_AddressStep> createState() => _AddressStepState();
}

class _AddressStepState extends ConsumerState<_AddressStep> {
  final _form = GlobalKey<FormState>();
  late final Map<String, dynamic> _a = Map<String, dynamic>.from(widget.ngo['address'] as Map? ?? {});
  late final _l1 = TextEditingController(text: _a['line1'] as String?);
  late final _l2 = TextEditingController(text: _a['line2'] as String?);
  late final _city = TextEditingController(text: _a['city'] as String?);
  late final _district = TextEditingController(text: _a['district'] as String?);
  late final _pin = TextEditingController(text: _a['pinCode'] as String?);
  late String? _state = _a['state'] as String? ?? 'Maharashtra';

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final v = V(t);
    return _FormStep(
      formKey: _form,
      label: t.continueLabel,
      onSave: () async {
        try {
          final ngo = await ref.read(apiProvider).put('/api/v1/ngos/${widget.ngo['id']}/address', body: {
            'line1': _l1.text.trim(), 'line2': _l2.text.trim(), 'city': _city.text.trim(),
            'district': _district.text.trim(), 'state': _state, 'pinCode': _pin.text.trim(),
          });
          widget.onSaved(ngo);
        } catch (e) {
          if (mounted) showSnack(context, ApiError.from(e).message, error: true);
        }
      },
      children: [
        TextFormField(controller: _l1, decoration: InputDecoration(labelText: t.line1), validator: v.text(min: 3, max: 200)),
        _gap(),
        TextFormField(controller: _l2, decoration: InputDecoration(labelText: t.line2), validator: v.text(optional: true, max: 200)),
        _gap(),
        TextFormField(controller: _city, decoration: InputDecoration(labelText: t.city), validator: v.text(min: 2, max: 80, pattern: V.name)),
        _gap(),
        TextFormField(controller: _district, decoration: InputDecoration(labelText: t.district), validator: v.text(min: 2, max: 80, pattern: V.name)),
        _gap(),
        DropdownButtonFormField<String>(
          initialValue: widget.states.contains(_state) ? _state : null,
          isExpanded: true,
          decoration: InputDecoration(labelText: t.state),
          items: [for (final s in widget.states) DropdownMenuItem(value: s, child: Text(s))],
          onChanged: (s) => setState(() => _state = s),
          validator: (s) => s == null ? t.required : null,
        ),
        _gap(),
        TextFormField(controller: _pin, keyboardType: TextInputType.number, maxLength: 6,
            decoration: InputDecoration(labelText: t.pinCode), validator: v.pinField),
      ],
    );
  }
}

class _PeopleStep extends ConsumerStatefulWidget {
  const _PeopleStep({required this.ngo, required this.onSaved});
  final Map<String, dynamic> ngo;
  final void Function(Map<String, dynamic>) onSaved;
  @override
  ConsumerState<_PeopleStep> createState() => _PeopleStepState();
}

class _PeopleStepState extends ConsumerState<_PeopleStep> {
  final _form = GlobalKey<FormState>();
  Map<String, dynamic> _contact(String kind) => Map<String, dynamic>.from(
      (widget.ngo['contacts'] as List? ?? const []).cast<Map>().firstWhere((c) => c['kind'] == kind, orElse: () => {}));
  late final _sig = _contact('SIGNATORY');
  late final _sec = _contact('SECONDARY_ADMIN');
  late final _sigName = TextEditingController(text: _sig['name'] as String?);
  late final _sigDesig = TextEditingController(text: _sig['designation'] as String?);
  late final _sigPhone = TextEditingController(text: _sig['phone'] as String?);
  late final _secName = TextEditingController(text: _sec['name'] as String?);
  late final _secEmail = TextEditingController(text: _sec['email'] as String?);
  late final _secPhone = TextEditingController(text: _sec['phone'] as String?);

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final v = V(t);
    final id = widget.ngo['id'];
    return _FormStep(
      formKey: _form,
      label: t.continueLabel,
      onSave: () async {
        try {
          final api = ref.read(apiProvider);
          var ngo = await api.put('/api/v1/ngos/$id/contacts/SIGNATORY', body: {
            'name': _sigName.text.trim(), 'designation': _sigDesig.text.trim(), 'phone': _sigPhone.text.trim(),
          });
          if (_secName.text.trim().isNotEmpty) {
            ngo = await api.put('/api/v1/ngos/$id/contacts/SECONDARY_ADMIN', body: {
              'name': _secName.text.trim(), 'email': _secEmail.text.trim(), 'phone': _secPhone.text.trim(),
              'designation': 'Administrator',
            });
          }
          widget.onSaved(ngo);
        } catch (e) {
          if (mounted) showSnack(context, ApiError.from(e).message, error: true);
        }
      },
      children: [
        Text(t.signatory, style: Theme.of(context).textTheme.titleMedium),
        Text(t.signatoryHint, style: const TextStyle(color: AppColors.textMuted)),
        _gap(),
        TextFormField(controller: _sigName, decoration: InputDecoration(labelText: t.fullName), validator: v.text(min: 2, max: 120, pattern: V.name)),
        _gap(),
        TextFormField(controller: _sigDesig, decoration: InputDecoration(labelText: t.designation), validator: v.text(min: 2, max: 80)),
        _gap(),
        TextFormField(controller: _sigPhone, keyboardType: TextInputType.phone, maxLength: 10,
            decoration: InputDecoration(labelText: t.mobile, prefixText: '+91 '), validator: v.phoneField(optional: true)),
        const Divider(height: 32),
        Text(t.secondaryAdmin, style: Theme.of(context).textTheme.titleMedium),
        Text(t.secondaryAdminHint, style: const TextStyle(color: AppColors.textMuted)),
        _gap(),
        TextFormField(controller: _secName, decoration: InputDecoration(labelText: t.fullName),
            validator: v.text(optional: true, min: 2, max: 120, pattern: V.name)),
        _gap(),
        TextFormField(controller: _secEmail, keyboardType: TextInputType.emailAddress, decoration: InputDecoration(labelText: t.email),
            validator: (s) => _secName.text.trim().isEmpty ? null : v.emailField()(s)),
        _gap(),
        TextFormField(controller: _secPhone, keyboardType: TextInputType.phone, maxLength: 10,
            decoration: InputDecoration(labelText: t.mobile, prefixText: '+91 '), validator: v.phoneField(optional: true)),
      ],
    );
  }
}

class _DocumentsStep extends ConsumerWidget {
  const _DocumentsStep({required this.ngoId, required this.ngo, required this.onDone});
  final String ngoId;
  final Map<String, dynamic> ngo;
  final Future<void> Function() onDone;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final docs = ref.watch(ngoDocumentsProvider(ngoId));
    final types = <(String, String, bool)>[
      ('REGISTRATION_CERTIFICATE', t.docRegistrationCertificate, true),
      ('ADDRESS_PROOF', t.docAddressProof, true),
      ('SIGNATORY_ID', t.docSignatoryId, true),
      ('PAN_CARD', t.docPanCard, ngo['pan'] != null),
      ('CERTIFICATE_80G', t.doc80g, ngo['tax80gNumber'] != null),
      ('TRUST_DEED_OR_MOA', t.docTrustDeed, false),
      ('CERTIFICATE_12A', t.doc12a, false),
      ('LOGO', t.docLogo, false),
    ];
    return Column(children: [
      Expanded(
        child: docs.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(ngoDocumentsProvider(ngoId))),
          data: (list) => ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            Text(t.fileRules, style: const TextStyle(color: AppColors.textMuted)),
            _gap(),
            for (final d in types)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: _DocTile(
                  ngoId: ngoId,
                  type: d.$1,
                  label: d.$2,
                  required: d.$3,
                  existing: list.where((x) => x['type'] == d.$1).firstOrNull,
                ),
              ),
          ]),
        ),
      ),
      SafeArea(
        top: false,
        child: Padding(padding: const EdgeInsets.all(AppSpacing.md), child: BusyButton(label: t.continueLabel, onPressed: onDone)),
      ),
    ]);
  }
}

class _DocTile extends ConsumerWidget {
  const _DocTile({required this.ngoId, required this.type, required this.label, required this.required, this.existing});
  final String ngoId;
  final String type;
  final String label;
  final bool required;
  final Map<String, dynamic>? existing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final status = existing?['status'] as String?;
    return AppCard(
      child: Row(children: [
        Icon(existing == null ? Icons.upload_file : (status == 'REJECTED' ? Icons.error_outline : Icons.check_circle),
            color: existing == null ? AppColors.textMuted : (status == 'REJECTED' ? AppColors.error : AppColors.success)),
        const SizedBox(width: AppSpacing.md),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
          Text(existing == null ? (required ? t.docRequired : t.docOptional) : '${existing!['fileName']}',
              maxLines: 1, overflow: TextOverflow.ellipsis,
              style: TextStyle(color: required && existing == null ? AppColors.error : AppColors.textMuted, fontSize: 13)),
          if (existing?['reviewNote'] != null) Text('${existing!['reviewNote']}', style: const TextStyle(color: AppColors.error, fontSize: 12)),
        ])),
        SizedBox(
          width: 112,
          child: BusyButton(
            outlined: existing != null,
            label: existing == null ? t.upload : t.replace,
            onPressed: () async {
              final f = await FilePicker.pickFile(
                type: FileType.custom,
                allowedExtensions: type == 'LOGO' ? ['png', 'jpg', 'jpeg'] : ['pdf', 'png', 'jpg', 'jpeg'],
              );
              if (f == null) return;
              try {
                final bytes = await f.readAsBytes();
                await ref.read(apiProvider).upload('/api/v1/ngos/$ngoId/documents',
                    bytes: bytes, filename: f.name, fields: {'type': type});
                ref.invalidate(ngoDocumentsProvider(ngoId));
                if (context.mounted) showSnack(context, t.uploaded);
              } catch (e) {
                if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
              }
            },
          ),
        ),
      ]),
    );
  }
}

class _ReviewStep extends ConsumerWidget {
  const _ReviewStep({required this.ngo, required this.onEdit, required this.onReload});
  final Map<String, dynamic> ngo;
  final void Function(int) onEdit;
  final Future<void> Function() onReload;

  int _stepFor(String item) => switch (item) {
        'REGISTRATION_DETAILS' => 1,
        'ADDRESS' => 2,
        'AUTHORIZED_SIGNATORY' => 3,
        'CONTACT_DETAILS' || 'DESCRIPTION' || 'AREAS_OF_ACTIVITY' => 0,
        _ => 4,
      };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final missing = List<String>.from(ngo['missingForSubmission'] as List? ?? const []);
    final a = ngo['address'] as Map?;
    return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
      AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${ngo['displayName']}', style: Theme.of(context).textTheme.titleLarge),
          Text('${ngo['legalName']}', style: const TextStyle(color: AppColors.textMuted)),
          const Divider(),
          KeyValueRow(t.registrationType, registrationTypeLabel(t, ngo['registrationType'] as String?)),
          KeyValueRow(t.registrationNumber, ngo['registrationNumber'] as String?),
          KeyValueRow(t.registrationState, ngo['registrationState'] as String?),
          KeyValueRow(t.darpanId, ngo['darpanId'] as String?),
          KeyValueRow(t.pan, ngo['pan'] as String?),
          KeyValueRow(t.officialEmail, ngo['officialEmail'] as String?),
          KeyValueRow(t.address, a == null ? null : '${a['city']}, ${a['state']} ${a['pinCode']}'),
        ]),
      ),
      _gap(),
      if (missing.isNotEmpty)
        AppCard(
          color: AppColors.warning.withValues(alpha: 0.10),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(t.missingItems, style: const TextStyle(fontWeight: FontWeight.w700)),
            for (final m in missing)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.radio_button_unchecked, size: 18),
                title: Text(m.startsWith('DOCUMENT_') ? documentTypeLabel(t, m.substring(9)) : humanizeCode(m)),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => onEdit(_stepFor(m)),
              ),
          ]),
        )
      else
        AppCard(color: AppColors.primarySoft, child: Text(t.readyToSubmit)),
      _gap(),
      BusyButton(
        label: t.submitForVerification,
        onPressed: missing.isNotEmpty
            ? null
            : () async {
                try {
                  await ref.read(apiProvider).post('/api/v1/ngos/${ngo['id']}/submit');
                  ref.invalidate(myRegistrationsProvider);
                  ref.invalidate(meProvider);
                  if (context.mounted) {
                    showSnack(context, t.submitted);
                    context.go('/ngo/start?stay=1');
                  }
                } catch (e) {
                  if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                  await onReload();
                }
              },
      ),
    ]);
  }
}
