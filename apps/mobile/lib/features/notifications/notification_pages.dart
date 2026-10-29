import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';

/// NOTIF-001..007: inbox, details, preferences, communication history (notification-service).

final unreadCountProvider = FutureProvider<int>((ref) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson('/api/v1/notifications/unread-count');
  return (r['unread'] as num?)?.toInt() ?? 0;
});

final _inboxProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson('/api/v1/notifications', query: {'size': '50'});
  return (r['items'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final _prefsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final l = await ref.read(apiProvider).getList('/api/v1/notifications/preferences');
  return l.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final _historyProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson('/api/v1/notifications/history');
  return (r['items'] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

/// App-bar bell with an unread badge. Fails quietly (the bell still opens the inbox).
class NotificationBell extends ConsumerWidget {
  const NotificationBell({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final n = ref.watch(unreadCountProvider).value ?? 0;
    return IconButton(
      tooltip: n > 0 ? '${t.notifications}, ${t.unreadCount(n)}' : t.notifications,
      icon: Badge(
        isLabelVisible: n > 0,
        label: Text(n > 99 ? '99+' : '$n'),
        child: const Icon(Icons.notifications_outlined),
      ),
      onPressed: () async {
        await context.push('/notifications');
        ref.invalidate(unreadCountProvider);
      },
    );
  }
}

String categoryLabel(L10n t, String? c) => switch (c) {
      'ACCOUNT' => t.catAccount,
      'TEAM' => t.catTeam,
      'BILLING' => t.catBilling,
      'DONATIONS' => t.catDonations,
      'CAMPAIGNS' => t.catCampaigns,
      'HELPDESK' => t.catHelpdesk,
      _ => c ?? '',
    };

IconData _categoryIcon(String? c) => switch (c) {
      'ACCOUNT' => Icons.verified_outlined,
      'TEAM' => Icons.group_outlined,
      'BILLING' => Icons.workspace_premium_outlined,
      'DONATIONS' => Icons.volunteer_activism_outlined,
      'CAMPAIGNS' => Icons.campaign_outlined,
      'HELPDESK' => Icons.support_agent,
      _ => Icons.notifications_outlined,
    };

class NotificationsPage extends ConsumerWidget {
  const NotificationsPage({super.key});

  Future<void> _open(BuildContext context, WidgetRef ref, Map<String, dynamic> n) async {
    if (n['read'] != true) {
      try {
        await ref.read(apiProvider).post('/api/v1/notifications/${n['id']}/read');
      } catch (_) {/* reading still works offline-ish; the badge refreshes later */}
      ref.invalidate(_inboxProvider);
      ref.invalidate(unreadCountProvider);
    }
    final link = n['link'] as String?;
    if (!context.mounted) return;
    if (link != null && link.startsWith('/')) {
      context.push(link);
    } else {
      showModalBottomSheet(
        context: context,
        builder: (_) => SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${n['title']}', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: AppSpacing.sm),
              Text('${n['body']}'),
            ]),
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final inbox = ref.watch(_inboxProvider);
    final hasUnread = inbox.value?.any((n) => n['read'] != true) ?? false;
    return Scaffold(
      appBar: AppBar(title: Text(t.notifications), actions: [
        if (hasUnread)
          IconButton(
            tooltip: t.markAllRead,
            icon: const Icon(Icons.done_all),
            onPressed: () async {
              try {
                await ref.read(apiProvider).post('/api/v1/notifications/read-all');
              } catch (e) {
                if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
              }
              ref.invalidate(_inboxProvider);
              ref.invalidate(unreadCountProvider);
            },
          ),
        IconButton(
          tooltip: t.notificationPreferences,
          icon: const Icon(Icons.tune),
          onPressed: () => context.push('/notifications/preferences'),
        ),
      ]),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(_inboxProvider);
          ref.invalidate(unreadCountProvider);
        },
        child: inbox.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_inboxProvider)),
          data: (list) => list.isEmpty
              ? ListView(children: [
                  EmptyState(icon: Icons.notifications_none, title: t.noNotifications, message: t.noNotificationsBody),
                ])
              : ListView.separated(
                  itemCount: list.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, i) {
                    final n = list[i];
                    final unread = n['read'] != true;
                    return ListTile(
                      leading: CircleAvatar(
                        backgroundColor: AppColors.primarySoft,
                        child: Icon(_categoryIcon(n['category'] as String?), color: AppColors.primary),
                      ),
                      title: Text('${n['title']}',
                          style: TextStyle(fontWeight: unread ? FontWeight.w700 : FontWeight.w400)),
                      subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${n['body']}', maxLines: 3, overflow: TextOverflow.ellipsis),
                        const SizedBox(height: 2),
                        Text(formatDate(n['createdAt']), style: const TextStyle(fontSize: 12, color: AppColors.textMuted)),
                      ]),
                      trailing: unread
                          ? Semantics(
                              label: t.statusLabel('PENDING'),
                              child: Container(width: 10, height: 10,
                                  decoration: const BoxDecoration(color: AppColors.primary, shape: BoxShape.circle)),
                            )
                          : null,
                      isThreeLine: true,
                      onTap: () => _open(context, ref, n),
                    );
                  },
                ),
        ),
      ),
    );
  }
}

class NotificationPreferencesPage extends ConsumerStatefulWidget {
  const NotificationPreferencesPage({super.key});
  @override
  ConsumerState<NotificationPreferencesPage> createState() => _PrefsState();
}

class _PrefsState extends ConsumerState<NotificationPreferencesPage> {
  bool _saving = false;

  Future<void> _save(Map<String, dynamic> p, {bool? email, bool? inApp}) async {
    setState(() => _saving = true);
    try {
      await ref.read(apiProvider).put('/api/v1/notifications/preferences', body: {
        'preferences': [
          {'category': p['category'], 'email': email ?? p['email'], 'inApp': inApp ?? p['inApp'], 'push': p['push']},
        ],
      });
      ref.invalidate(_prefsProvider);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final prefs = ref.watch(_prefsProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t.notificationPreferences), actions: [
        IconButton(
          tooltip: t.communicationHistory,
          icon: const Icon(Icons.history),
          onPressed: () => context.push('/notifications/history'),
        ),
      ]),
      body: prefs.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_prefsProvider)),
        data: (list) => AbsorbPointer(
          absorbing: _saving,
          child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            for (final p in list)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: AppCard(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      Icon(_categoryIcon(p['category'] as String?), color: AppColors.primary),
                      const SizedBox(width: AppSpacing.sm),
                      Expanded(child: Text(categoryLabel(t, p['category'] as String?),
                          style: Theme.of(context).textTheme.titleMedium)),
                      if (p['mandatory'] == true) const Icon(Icons.lock_outline, size: 18, color: AppColors.textMuted),
                    ]),
                    if (p['mandatory'] == true)
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(t.mandatoryNotifications, style: const TextStyle(color: AppColors.textMuted)),
                      ),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(t.channelEmail),
                      value: p['email'] == true,
                      onChanged: p['mandatory'] == true ? null : (v) => _save(p, email: v),
                    ),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(t.channelInApp),
                      value: p['inApp'] == true,
                      onChanged: p['mandatory'] == true ? null : (v) => _save(p, inApp: v),
                    ),
                  ]),
                ),
              ),
            Text(t.pushComingSoon, style: const TextStyle(color: AppColors.textMuted)),
          ]),
        ),
      ),
    );
  }
}

class CommunicationHistoryPage extends ConsumerWidget {
  const CommunicationHistoryPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final h = ref.watch(_historyProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t.communicationHistory)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(_historyProvider),
        child: h.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_historyProvider)),
          data: (list) => list.isEmpty
              ? ListView(children: [EmptyState(icon: Icons.mail_outline, title: t.noMessagesYet)])
              : ListView.separated(
                  itemCount: list.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, i) {
                    final d = list[i];
                    final status = '${d['status']}';
                    return ListTile(
                      leading: const Icon(Icons.mail_outline),
                      title: Text('${d['subject'] ?? ''}', maxLines: 2, overflow: TextOverflow.ellipsis),
                      subtitle: Text('${d['recipient']} · ${formatDate(d['createdAt'])}'),
                      trailing: StatusBadge(status, tone: StatusBadge.toneFor(status == 'SENT' ? 'DELIVERED' : status == 'DEAD' ? 'FAILED' : status)),
                    );
                  },
                ),
        ),
      ),
    );
  }
}
