import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/auth_service.dart';
import '../../core/session/preferences.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../onboarding/onboarding_pages.dart';
import 'avatar.dart';

/// Shown once after the first sign-in (the server blocks everything else until it is saved: adults only).
class CompleteProfilePage extends ConsumerWidget {
  const CompleteProfilePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(t.completeProfileTitle),
        automaticallyImplyLeading: false,
        actions: [
          IconButton(
            tooltip: t.signOut,
            icon: const Icon(Icons.logout),
            onPressed: () async {
              await ref.read(prefsProvider.notifier).resetForSignOut();
              await ref.read(authServiceProvider).signOut();
            },
          ),
        ],
      ),
      body: const ProfileForm(first: true),
    );
  }
}

/// Settings > My profile.
class MyProfilePage extends StatelessWidget {
  const MyProfilePage({super.key});

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Scaffold(appBar: AppBar(title: Text(t.myProfile)), body: const ProfileForm(first: false));
  }
}

class ProfileForm extends ConsumerStatefulWidget {
  const ProfileForm({super.key, required this.first});
  final bool first;
  @override
  ConsumerState<ProfileForm> createState() => _ProfileFormState();
}

class _ProfileFormState extends ConsumerState<ProfileForm> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  String _language = 'en';
  String _gender = 'UNDISCLOSED';
  DateTime? _birthDate;
  bool _dobLocked = false;
  bool _confirm = false;
  bool _confirmError = false;
  bool _pictureBusy = false;
  bool _loaded = false;

  void _load(Me me) {
    if (_loaded) return;
    _loaded = true;
    _name.text = me.displayName ?? '';
    _language = me.language;
    _gender = me.gender;
    if (me.birthDate != null) {
      _birthDate = DateTime.tryParse(me.birthDate!);
      _dobLocked = _birthDate != null;
    }
    _confirm = !widget.first;
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  static int _age(DateTime dob, DateTime today) {
    var years = today.year - dob.year;
    if (today.month < dob.month || (today.month == dob.month && today.day < dob.day)) years--;
    return years;
  }

  Future<void> _pickDate() async {
    if (_dobLocked) return;
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _birthDate ?? DateTime(now.year - 30, now.month, now.day),
      firstDate: DateTime(1900),
      lastDate: now,
      initialEntryMode: DatePickerEntryMode.calendar,
      initialDatePickerMode: DatePickerMode.year,
    );
    if (picked != null) setState(() => _birthDate = picked);
  }

  Future<void> _save() async {
    final t = L10n.of(context);
    setState(() => _confirmError = widget.first && !_confirm);
    final valid = _form.currentState?.validate() ?? false;
    if (!valid || _birthDate == null || _confirmError) {
      if (_birthDate == null) showSnack(context, '${t.dateOfBirth}: ${t.required}', error: true);
      return;
    }
    final me = ref.read(meProvider).value;
    if (me != null && me.phoneRequired && !me.phoneVerified) {
      showSnack(context, t.mobileRequiredForTeam, error: true);
      return;
    }
    if (_age(_birthDate!, DateTime.now()) < 18) {
      final go = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: Text(t.underageTitle),
          content: Text(t.underageConfirm),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: Text(t.checkDate)),
            FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(t.continueLabel)),
          ],
        ),
      );
      if (go != true) return;
    }
    try {
      await ref.read(apiProvider).put('/api/v1/me/profile', body: {
        'displayName': _name.text.trim(),
        'preferredLanguage': _language,
        'birthDate': DateFormat('yyyy-MM-dd').format(_birthDate!),
        'gender': _gender,
        'confirmAdult': _confirm,
      });
      await ref.read(prefsProvider.notifier).setLocale(Locale(_language));
      ref.invalidate(meProvider);
      if (mounted) {
        showSnack(context, t.saved);
        if (!widget.first && Navigator.of(context).canPop()) Navigator.of(context).pop();
      }
    } catch (e) {
      final err = ApiError.from(e);
      if (err.code == 'UNDERAGE') {
        if (!mounted) return;
        await showDialog<void>(
          context: context,
          barrierDismissible: false,
          builder: (c) => AlertDialog(
            title: Text(t.underageTitle),
            content: Text(err.message),
            actions: [FilledButton(onPressed: () => Navigator.pop(c), child: Text(t.ok))],
          ),
        );
        await ref.read(prefsProvider.notifier).resetForSignOut();
        await ref.read(authServiceProvider).signOutLocally();
        return;
      }
      if (mounted) showSnack(context, err.message, error: true);
    }
  }

  // ---------------------------------------------------------------- picture

  Future<void> _pictureMenu(Me me) async {
    final t = L10n.of(context);
    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (c) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(leading: const Icon(Icons.photo_camera_outlined), title: Text(t.takePhoto), onTap: () => Navigator.pop(c, 'camera')),
          ListTile(leading: const Icon(Icons.photo_library_outlined), title: Text(t.chooseFromGallery), onTap: () => Navigator.pop(c, 'gallery')),
          ListTile(leading: const Icon(Icons.emoji_emotions_outlined), title: Text(t.chooseAvatar), onTap: () => Navigator.pop(c, 'avatar')),
          if ((me.avatar?['kind'] ?? 'NONE') != 'NONE')
            ListTile(leading: const Icon(Icons.delete_outline, color: AppColors.error), title: Text(t.removePicture),
                onTap: () => Navigator.pop(c, 'remove')),
        ]),
      ),
    );
    if (choice == null || !mounted) return;
    setState(() => _pictureBusy = true);
    try {
      final api = ref.read(apiProvider);
      switch (choice) {
        case 'camera':
        case 'gallery':
          final file = await ImagePicker().pickImage(
            source: choice == 'camera' ? ImageSource.camera : ImageSource.gallery,
            maxWidth: 1600,
            maxHeight: 1600,
            imageQuality: 90,
            preferredCameraDevice: CameraDevice.front,
          );
          if (file == null) break;
          await api.upload('/api/v1/me/photo', bytes: await file.readAsBytes(), filename: 'photo.jpg');
        case 'avatar':
          final preset = await _chooseAvatar();
          if (preset == null) break;
          await api.put('/api/v1/me/avatar', body: {'preset': preset});
        case 'remove':
          await api.delete('/api/v1/me/photo');
      }
      ref.invalidate(meProvider);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _pictureBusy = false);
    }
  }

  Future<String?> _chooseAvatar() {
    final t = L10n.of(context);
    return showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(t.chooseAvatar),
        content: SizedBox(
          width: 320,
          child: GridView.count(
            crossAxisCount: 4,
            shrinkWrap: true,
            mainAxisSpacing: 12,
            crossAxisSpacing: 12,
            children: [
              for (final e in avatarPresets.entries)
                InkWell(
                  customBorder: const CircleBorder(),
                  onTap: () => Navigator.pop(c, e.key),
                  child: CircleAvatar(backgroundColor: e.value.$2, child: Icon(e.value.$1, color: Colors.white)),
                ),
            ],
          ),
        ),
        actions: [TextButton(onPressed: () => Navigator.pop(c), child: Text(t.cancel))],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final v = V(t);
    final me = ref.watch(meProvider).value;
    if (me == null) return const LoadingView();
    _load(me);
    final locale = Localizations.localeOf(context).toLanguageTag();
    return Form(
      key: _form,
      child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
        if (widget.first) ...[
          Text(t.completeProfileIntro, style: const TextStyle(color: AppColors.textMuted)),
          if (me.phoneRequired && !me.phoneVerified) ...[
            const SizedBox(height: AppSpacing.sm),
            AppCard(color: AppColors.primarySoft, child: Text(t.mobileRequiredForTeam)),
          ],
          const SizedBox(height: AppSpacing.md),
        ],
        // Picture
        Center(
          child: Stack(clipBehavior: Clip.none, children: [
            UserAvatar(userId: me.userId, name: _name.text.isEmpty ? me.name : _name.text, avatar: me.avatar, radius: 48, self: true),
            Positioned(
              right: -4,
              bottom: -4,
              child: Material(
                color: AppColors.primary,
                shape: const CircleBorder(side: BorderSide(color: Colors.white, width: 2)),
                child: IconButton(
                  tooltip: t.changePicture,
                  visualDensity: VisualDensity.compact,
                  constraints: const BoxConstraints.tightFor(width: 36, height: 36),
                  padding: EdgeInsets.zero,
                  icon: _pictureBusy
                      ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Icon(Icons.photo_camera, color: Colors.white, size: 18),
                  onPressed: _pictureBusy ? null : () => _pictureMenu(me),
                ),
              ),
            ),
          ]),
        ),
        const SizedBox(height: 6),
        Center(child: Text(t.profilePicture, style: Theme.of(context).textTheme.titleSmall)),
        Text(t.profilePictureHelp, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        const SizedBox(height: AppSpacing.lg),
        TextFormField(
          controller: _name,
          decoration: InputDecoration(labelText: t.fullName),
          textCapitalization: TextCapitalization.words,
          validator: v.text(min: 2, max: 120, pattern: RegExp(r"^[\p{L}\p{M} .'-]+$", unicode: true)),
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: AppSpacing.sm),
        _PhoneTile(me: me),
        const SizedBox(height: AppSpacing.md),
        DropdownButtonFormField<String>(
          initialValue: _language,
          decoration: InputDecoration(labelText: t.language),
          items: [for (final o in LanguagePage.options) DropdownMenuItem(value: o.$1, child: Text(o.$2))],
          onChanged: (x) => setState(() => _language = x ?? 'en'),
        ),
        const SizedBox(height: AppSpacing.md),
        // Date of birth
        InkWell(
          onTap: _dobLocked ? null : _pickDate,
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: t.dateOfBirth,
              helperText: _dobLocked ? t.dateOfBirthLocked : t.dateOfBirthHelp,
              helperMaxLines: 3,
              suffixIcon: Icon(_dobLocked ? Icons.lock_outline : Icons.calendar_today_outlined),
              enabled: !_dobLocked,
            ),
            child: Text(_birthDate == null ? t.chooseDate : DateFormat.yMMMd(locale).format(_birthDate!)),
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        DropdownButtonFormField<String>(
          initialValue: _gender,
          decoration: InputDecoration(labelText: t.gender, helperText: t.genderHelp, helperMaxLines: 3),
          items: [
            DropdownMenuItem(value: 'UNDISCLOSED', child: Text(t.genderUndisclosed)),
            DropdownMenuItem(value: 'FEMALE', child: Text(t.genderFemale)),
            DropdownMenuItem(value: 'MALE', child: Text(t.genderMale)),
            DropdownMenuItem(value: 'TRANSGENDER', child: Text(t.genderTransgender)),
          ],
          onChanged: (x) => setState(() => _gender = x ?? 'UNDISCLOSED'),
        ),
        if (widget.first) ...[
          const SizedBox(height: AppSpacing.md),
          CheckboxListTile(
            value: _confirm,
            onChanged: (x) => setState(() { _confirm = x ?? false; _confirmError = false; }),
            title: Text(t.confirmAdult),
            subtitle: _confirmError ? Text(t.confirmAdultRequired, style: const TextStyle(color: AppColors.error)) : null,
            controlAffinity: ListTileControlAffinity.leading,
            contentPadding: EdgeInsets.zero,
          ),
        ],
        const SizedBox(height: AppSpacing.md),
        BusyButton(label: widget.first ? t.continueLabel : t.save, onPressed: _save),
        const SizedBox(height: AppSpacing.lg),
      ]),
    );
  }
}


/// Verified mobile number: shows the number with a "Verified" badge, or a button to add one by SMS code.
class _PhoneTile extends ConsumerWidget {
  const _PhoneTile({required this.me});
  final Me me;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final verified = me.phoneVerified && me.phone != null;
    return InputDecorator(
      decoration: InputDecoration(
        labelText: me.phoneRequired ? t.mobileNumber : t.mobileOptional,
        helperText: me.phoneRequired && !verified ? t.mobileRequiredForTeam : t.mobilePrivacy,
        helperMaxLines: 2,
      ),
      child: Row(children: [
        Expanded(
          child: verified
              ? Row(children: [
                  Text('+91 ${me.phone}', style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(width: 8),
                  const Icon(Icons.verified, size: 18, color: AppColors.success),
                  const SizedBox(width: 4),
                  Flexible(child: Text(t.verified, style: const TextStyle(color: AppColors.success))),
                ])
              : Text(t.addMobile, style: const TextStyle(color: AppColors.textMuted)),
        ),
        TextButton(
          onPressed: () async {
            final ok = await showModalBottomSheet<bool>(
              context: context,
              isScrollControlled: true,
              builder: (_) => const PhoneVerifySheet(),
            );
            if (ok == true) {
              ref.invalidate(meProvider);
              if (context.mounted) showSnack(context, t.phoneVerifiedDone);
            }
          },
          child: Text(verified ? t.changeMobile : t.verify),
        ),
      ]),
    );
  }
}

/// Two steps: number -> SMS code. The number is saved only when the code is confirmed.
class PhoneVerifySheet extends ConsumerStatefulWidget {
  const PhoneVerifySheet({super.key});
  @override
  ConsumerState<PhoneVerifySheet> createState() => _PhoneVerifySheetState();
}

class _PhoneVerifySheetState extends ConsumerState<PhoneVerifySheet> {
  final _number = TextEditingController();
  final _code = TextEditingController();
  bool _codeSent = false;
  bool _verifying = false;
  int _wait = 0;
  String? _error;
  Timer? _timer;

  @override
  void dispose() {
    _timer?.cancel();
    _number.dispose();
    _code.dispose();
    super.dispose();
  }

  void _startCountdown(int seconds) {
    _timer?.cancel();
    setState(() => _wait = seconds);
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) return t.cancel();
      setState(() => _wait = _wait > 0 ? _wait - 1 : 0);
      if (_wait == 0) t.cancel();
    });
  }

  Future<void> _send() async {
    final t = L10n.of(context);
    final n = _number.text.trim();
    if (!V.phone.hasMatch(n)) {
      setState(() => _error = t.invalidPhone);
      return;
    }
    try {
      final r = await ref.read(apiProvider).post('/api/v1/me/phone/otp', body: {'phone': n});
      setState(() { _codeSent = true; _error = null; _code.clear(); });
      _startCountdown((r['resendAfterSeconds'] as num?)?.toInt() ?? 60);
    } catch (e) {
      setState(() => _error = ApiError.from(e).message);
    }
  }

  bool get _codeReady => RegExp(r'^\d{6}$').hasMatch(_code.text.trim());

  Future<void> _verify() async {
    if (!_codeReady || _verifying) return;
    _verifying = true;
    try {
      await ref.read(apiProvider).post('/api/v1/me/phone/verify', body: {'phone': _number.text.trim(), 'code': _code.text.trim()});
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) setState(() => _error = ApiError.from(e).message);
    } finally {
      _verifying = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Padding(
      padding: EdgeInsets.only(left: AppSpacing.lg, right: AppSpacing.lg, top: AppSpacing.lg,
          bottom: MediaQuery.viewInsetsOf(context).bottom + AppSpacing.lg),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(t.mobileNumber, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(t.mobilePrivacy, style: const TextStyle(color: AppColors.textMuted)),
        const SizedBox(height: AppSpacing.md),
        TextField(
          controller: _number,
          enabled: !_codeSent,
          keyboardType: TextInputType.phone,
          maxLength: 10,
          autofocus: true,
          decoration: InputDecoration(labelText: t.mobileNumber, prefixText: '+91 ', counterText: ''),
        ),
        if (_codeSent) ...[
          Text(t.codeSentTo(_number.text.trim()), style: const TextStyle(color: AppColors.textMuted)),
          const SizedBox(height: AppSpacing.sm),
          TextField(
            controller: _code,
            autofocus: true,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            maxLength: 6,
            onChanged: (_) {
              setState(() => _error = null);
              if (_codeReady) _verify();
            },
            autofillHints: const [AutofillHints.oneTimeCode],
            decoration: InputDecoration(labelText: t.enterCode, counterText: ''),
            style: const TextStyle(fontSize: 22, letterSpacing: 8, fontWeight: FontWeight.w700),
          ),
        ],
        if (_error != null) Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(_error!, style: const TextStyle(color: AppColors.error)),
        ),
        const SizedBox(height: AppSpacing.md),
        if (!_codeSent)
          BusyButton(label: t.sendCode, onPressed: _send)
        else ...[
          BusyButton(label: t.verify, onPressed: _codeReady ? _verify : null),
          TextButton(
            onPressed: _wait > 0 ? null : _send,
            child: Text(_wait > 0 ? t.resendIn('$_wait') : t.resendCode),
          ),
        ],
      ]),
    );
  }
}
