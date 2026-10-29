import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../profile/avatar.dart';

final _staffProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  final api = ref.read(apiProvider);
  final results = await Future.wait([
    api.getList('/api/v1/ngos/$ngoId/staff'),
    api.getJson('/api/v1/ngos/$ngoId/staff/capacity'),
    api.getList('/api/v1/ngos/$ngoId/staff/invitations'),
  ]);
  return {'members': results[0], 'capacity': results[1], 'invitations': results[2]};
});

/// The platform allows at most 2 administrators per NGO (server rule ADMIN_LIMIT_REACHED); pending admin
/// invitations reserve a seat. Unknown capacity -> offer the role and let the server decide.
bool _adminSeatFree(Object? capacity) {
  if (capacity is! Map) return true;
  final max = (capacity['adminMax'] as num?)?.toInt() ?? 2;
  final used = ((capacity['activeAdmins'] as num?)?.toInt() ?? 0) + ((capacity['pendingAdmins'] as num?)?.toInt() ?? 0);
  return used < max;
}

const _roles = ['STAFF', 'FINANCE', 'CAMPAIGN_MANAGER', 'VOLUNTEER_COORDINATOR', 'VOLUNTEER', 'SECONDARY_ADMIN'];
String roleLabel(L10n t, String r) => t.roleName(r);

/// STAFF-001..022: directory, capacity, invitations, activation.
class StaffPage extends ConsumerWidget {
  const StaffPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider)!;
    final m = ref.watch(currentMembershipProvider);
    final canManage = m?.can('STAFF_MANAGE') ?? false;
    final data = ref.watch(_staffProvider(ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.staffDirectory)),
      floatingActionButton: canManage
          ? FloatingActionButton.extended(
              onPressed: () => showModalBottomSheet(context: context, isScrollControlled: true,
                  builder: (_) => _InviteSheet(ngoId: ngoId, isAdmin: m?.isAdmin ?? false,
                      adminSeatFree: _adminSeatFree(data.value?['capacity']))),
              icon: const Icon(Icons.person_add),
              label: Text(t.inviteStaff),
            )
          : null,
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(_staffProvider(ngoId)),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_staffProvider(ngoId))),
          data: (d) {
            final cap = Map<String, dynamic>.from(d['capacity'] as Map);
            final members = (d['members'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
            final invites = (d['invitations'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
            final max = (cap['staffMax'] as num).toInt();
            final used = (cap['activeStaff'] as num).toInt() + (cap['pendingReservesSeat'] == true ? (cap['pendingStaff'] as num).toInt() : 0);
            return ListView(padding: const EdgeInsets.fromLTRB(16, 16, 16, 96), children: [
              AppCard(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(t.seatsUsed('$used', max < 0 ? '∞' : '$max'), style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 8),
                  if (max > 0) LinearProgressIndicator(value: (used / max).clamp(0, 1), minHeight: 8, borderRadius: BorderRadius.circular(8)),
                  const SizedBox(height: 8),
                  Text(t.adminsUsed(((cap['activeAdmins'] as num?)?.toInt() ?? 0) + ((cap['pendingAdmins'] as num?)?.toInt() ?? 0),
                      (cap['adminMax'] as num?)?.toInt() ?? 2), style: const TextStyle(color: AppColors.textMuted)),
                ]),
              ),
              const SizedBox(height: AppSpacing.md),
              for (final mem in members)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                  child: AppCard(
                    child: Row(children: [
                      UserAvatar(
                        userId: '${mem['userId']}',
                        name: '${mem['displayName'] ?? mem['email'] ?? '?'}',
                        avatar: (mem['avatar'] as Map?)?.cast<String, dynamic>(),
                        self: mem['userId'] == ref.read(meProvider).value?.userId,
                      ),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${mem['displayName'] ?? mem['email']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text(roleLabel(t, '${mem['role']}'), style: const TextStyle(color: AppColors.textMuted)),
                        // Contact details are visible only inside the NGO team.
                        if (mem['phone'] != null)
                          Text('+91 ${mem['phone']}', style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                      ])),
                      if (mem['phone'] != null)
                        IconButton(
                          tooltip: t.call,
                          icon: const Icon(Icons.call_outlined, color: AppColors.primary),
                          onPressed: () => launchUrl(Uri.parse('tel:+91${mem['phone']}')),
                        ),
                      StatusBadge('${mem['status']}', tone: StatusBadge.toneFor('${mem['status']}')),
                      if (canManage && mem['role'] != 'PRIMARY_ADMIN' && mem['userId'] != ref.read(meProvider).value?.userId)
                        PopupMenuButton<String>(
                          onSelected: (s) async {
                            try {
                              await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/staff/${mem['membershipId']}/status', body: {'status': s});
                              ref.invalidate(_staffProvider(ngoId));
                            } catch (e) {
                              if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                            }
                          },
                          itemBuilder: (_) => [
                            if (mem['status'] == 'ACTIVE') PopupMenuItem(value: 'DEACTIVATED', child: Text(t.deactivate)),
                            if (mem['status'] != 'ACTIVE') PopupMenuItem(value: 'ACTIVE', child: Text(t.activate)),
                          ],
                        ),
                    ]),
                  ),
                ),
              if (invites.isNotEmpty) SectionHeader(t.pending),
              for (final i in invites)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                  child: AppCard(
                    child: Row(children: [
                      const Icon(Icons.mail_outline),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${i['email']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text('${roleLabel(t, '${i['role']}')} · ${t.inviteExpires(formatDate(i['expiresAt']))}', style: const TextStyle(color: AppColors.textMuted)),
                      ])),
                      if (canManage)
                        IconButton(
                          tooltip: t.cancel,
                          icon: const Icon(Icons.close),
                          onPressed: () async {
                            try {
                              await ref.read(apiProvider).delete('/api/v1/ngos/$ngoId/staff/invitations/${i['id']}');
                              ref.invalidate(_staffProvider(ngoId));
                            } catch (e) {
                              if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                            }
                          },
                        ),
                    ]),
                  ),
                ),
            ]);
          },
        ),
      ),
    );
  }
}

class _InviteSheet extends ConsumerStatefulWidget {
  const _InviteSheet({required this.ngoId, required this.isAdmin, required this.adminSeatFree});
  final String ngoId;
  final bool isAdmin;
  final bool adminSeatFree;
  @override
  ConsumerState<_InviteSheet> createState() => _InviteSheetState();
}

class _InviteSheetState extends ConsumerState<_InviteSheet> {
  final _form = GlobalKey<FormState>();
  final _email = TextEditingController();
  String _role = 'STAFF';

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SafeArea(
        child: Form(
          key: _form,
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text(t.inviteStaff, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: AppSpacing.md),
              TextFormField(controller: _email, keyboardType: TextInputType.emailAddress,
                  decoration: InputDecoration(labelText: t.email), validator: V(t).emailField()),
              const SizedBox(height: AppSpacing.md),
              DropdownButtonFormField<String>(
                initialValue: _role,
                decoration: InputDecoration(labelText: t.role),
                items: [
                  for (final r in _roles)
                    if (r != 'SECONDARY_ADMIN' || (widget.isAdmin && widget.adminSeatFree))
                      DropdownMenuItem(value: r, child: Text(roleLabel(t, r))),
                ],
                onChanged: (r) => setState(() => _role = r ?? _role),
              ),
              if (widget.isAdmin && !widget.adminSeatFree)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(t.adminLimitReachedHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                ),
              const SizedBox(height: AppSpacing.lg),
              BusyButton(
                label: t.invite,
                onPressed: () async {
                  if (!_form.currentState!.validate()) return;
                  try {
                    await ref.read(apiProvider).post('/api/v1/ngos/${widget.ngoId}/staff/invitations',
                        body: {'email': _email.text.trim(), 'role': _role});
                    ref.invalidate(_staffProvider(widget.ngoId));
                    if (context.mounted) {
                      Navigator.pop(context);
                      showSnack(context, t.invitationSent);
                    }
                  } catch (e) {
                    if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                  }
                },
              ),
            ]),
          ),
        ),
      ),
    );
  }
}
