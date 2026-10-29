import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/config/app_config.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../registration/registration_pages.dart' show causeLabel;
import 'campaign_api.dart';

String _day(Object? iso) {
  final d = parseDay(iso);
  return d == null ? '—' : DateFormat('d MMM yyyy').format(d);
}

// ============================================================ shared widgets

/// 16:9 cover: the uploaded photo, or a calm placeholder with the cause.
class CampaignCover extends ConsumerWidget {
  const CampaignCover({super.key, required this.campaign, this.ngoId, this.small = false, this.radius = AppRadius.card});
  final Map<String, dynamic> campaign;
  /// Set for team views (cover of a draft needs the member's token); null for public views.
  final String? ngoId;
  final bool small;
  final double radius;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final placeholder = Container(
      decoration: BoxDecoration(
        gradient: const LinearGradient(colors: [AppColors.primarySoft, Color(0xFFD7EBDD)],
            begin: Alignment.topLeft, end: Alignment.bottomRight),
        borderRadius: BorderRadius.circular(radius),
      ),
      alignment: Alignment.center,
      child: Icon(Icons.volunteer_activism, size: small ? 32 : 56, color: AppColors.primary.withValues(alpha: 0.55)),
    );
    final size = small ? 'small' : 'large';
    Widget image;
    if (campaign['hasImage'] != true) {
      image = placeholder;
    } else if (ngoId == null) {
      image = ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: Image.network(
          '${AppConfig.apiBaseUrl}/api/v1/public/campaigns/${campaign['id']}/image?size=$size&v=${campaign['imageVersion'] ?? 0}',
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => placeholder,
        ),
      );
    } else {
      final bytes = ref.watch(teamCoverProvider((ngoId!, '${campaign['id']}', (campaign['imageVersion'] as num?)?.toInt() ?? 0, size)));
      image = bytes.when(
        data: (b) => ClipRRect(borderRadius: BorderRadius.circular(radius), child: Image.memory(b, fit: BoxFit.cover)),
        loading: () => placeholder,
        error: (_, __) => placeholder,
      );
    }
    return AspectRatio(aspectRatio: 16 / 9, child: image);
  }
}

/// Swipeable photo gallery (16:9) with page dots; falls back to the cover/placeholder when there are no photos.
class CampaignGallery extends ConsumerStatefulWidget {
  const CampaignGallery({super.key, required this.campaign, this.ngoId});
  final Map<String, dynamic> campaign;
  /// Team view (needs token) when set; public URLs otherwise.
  final String? ngoId;
  @override
  ConsumerState<CampaignGallery> createState() => _CampaignGalleryState();
}

class _CampaignGalleryState extends ConsumerState<CampaignGallery> {
  int _page = 0;
  @override
  Widget build(BuildContext context) {
    final c = widget.campaign;
    final ids = (c['photoIds'] as List? ?? const []).map((e) => '$e').toList();
    if (ids.isEmpty) return CampaignCover(campaign: c, ngoId: widget.ngoId);
    final t = L10n.of(context);
    Widget photo(String id) {
      if (widget.ngoId == null) {
        return Image.network(publicPhotoUrl('${c['id']}', id), fit: BoxFit.cover,
            errorBuilder: (_, __, ___) => const ColoredBox(color: AppColors.primarySoft));
      }
      final b = ref.watch(teamPhotoProvider((widget.ngoId!, '${c['id']}', id, 'large')));
      return b.when(data: (x) => Image.memory(x, fit: BoxFit.cover),
          loading: () => const ColoredBox(color: AppColors.primarySoft), error: (_, __) => const ColoredBox(color: AppColors.primarySoft));
    }
    return Column(children: [
      AspectRatio(
        aspectRatio: 16 / 9,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(AppRadius.card),
          child: Semantics(
            label: t.photoOf(_page + 1, ids.length),
            child: PageView(onPageChanged: (i) => setState(() => _page = i), children: [for (final id in ids) photo(id)]),
          ),
        ),
      ),
      if (ids.length > 1)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            for (var i = 0; i < ids.length; i++)
              AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                margin: const EdgeInsets.symmetric(horizontal: 3),
                width: i == _page ? 18 : 7,
                height: 7,
                decoration: BoxDecoration(color: i == _page ? AppColors.primary : AppColors.outline, borderRadius: BorderRadius.circular(4)),
              ),
          ]),
        ),
    ]);
  }
}

/// "Share" + "Copy link" for a public campaign.
class CampaignShareRow extends StatelessWidget {
  const CampaignShareRow({super.key, required this.campaign});
  final Map<String, dynamic> campaign;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    if (campaign['shareUrl'] == null) return const SizedBox.shrink();
    return Row(children: [
      Expanded(
        child: Builder(
          builder: (bc) => OutlinedButton.icon(
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () => shareCampaign(bc, campaign),
            icon: const Icon(Icons.share_outlined),
            label: Text(t.shareCampaign),
          ),
        ),
      ),
      const SizedBox(width: AppSpacing.sm),
      IconButton.outlined(
        tooltip: t.copyLink,
        constraints: const BoxConstraints(minWidth: kMinTouchTarget, minHeight: kMinTouchTarget),
        onPressed: () async {
          await copyCampaignLink(campaign);
          if (context.mounted) showSnack(context, t.linkCopied);
        },
        icon: const Icon(Icons.link),
      ),
    ]);
  }
}

/// Progress bar with raised / goal, number of donations and time left.
class CampaignProgress extends StatelessWidget {
  const CampaignProgress({super.key, required this.campaign, this.compact = false});
  final Map<String, dynamic> campaign;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final c = campaign;
    final timing = timingText(t, c, _day);
    final count = (c['donationCount'] as num?)?.toInt() ?? 0;
    final pct = (progressOf(c) * 100).floor();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Semantics(
        label: t.raisedOfGoal(formatInr(c['raisedAmount'], whole: true), formatInr(c['goalAmount'], whole: true)),
        child: LinearProgressIndicator(value: progressOf(c), minHeight: compact ? 6 : 10, borderRadius: BorderRadius.circular(8),
            backgroundColor: AppColors.outline),
      ),
      const SizedBox(height: 6),
      Row(children: [
        Expanded(
          child: Text.rich(TextSpan(children: [
            TextSpan(text: formatInr(c['raisedAmount'], whole: true),
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: compact ? 14 : 18, color: AppColors.text)),
            TextSpan(text: '  ${t.ofGoal(formatInr(c['goalAmount'], whole: true))}', style: const TextStyle(color: AppColors.textMuted)),
          ])),
        ),
        Text('$pct%', style: const TextStyle(fontWeight: FontWeight.w700, color: AppColors.primary)),
      ]),
      if (!compact || timing.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Text([if (!compact) t.donationsCount(count), if (timing.isNotEmpty) timing].join(' · '),
              style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
        ),
    ]);
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip(this.status);
  final String status;
  @override
  Widget build(BuildContext context) =>
      StatusBadge(campaignStatusText(L10n.of(context), status), color: statusColor(status));
}

// ============================================================ NGO: list (CAMP-001)

class CampaignsTab extends ConsumerStatefulWidget {
  const CampaignsTab({super.key});
  @override
  ConsumerState<CampaignsTab> createState() => _CampaignsTabState();
}

class _CampaignsTabState extends ConsumerState<CampaignsTab> {
  String? _status;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final m = ref.watch(currentMembershipProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final filter = CampaignFilter(ngoId, _status);
    final list = ref.watch(ngoCampaignsProvider(filter));
    final summary = ref.watch(campaignSummaryProvider(ngoId)).value;
    final counts = (summary?['counts'] as Map?)?.cast<String, dynamic>() ?? const {};
    int count(String s) => (counts[s] as num?)?.toInt() ?? 0;
    final pending = count(CampaignStatus.pending);
    final live = count(CampaignStatus.live);
    final limit = (summary?['liveLimit'] as num?)?.toInt();
    final canCreate = m?.can('CAMPAIGN_MANAGE') ?? false;

    final filters = <(String?, String)>[
      (null, t.filterAll),
      (CampaignStatus.pending, '${t.campaignStatusLabel(CampaignStatus.pending)}${pending > 0 ? ' ($pending)' : ''}'),
      (CampaignStatus.live, t.campaignStatusLabel(CampaignStatus.live)),
      (CampaignStatus.draft, t.filterDrafts),
      (CampaignStatus.closed, t.campaignStatusLabel(CampaignStatus.closed)),
    ];

    return Scaffold(
      appBar: AppBar(title: Text(t.navCampaigns)),
      floatingActionButton: canCreate
          ? FloatingActionButton.extended(
              onPressed: () => context.push('/ngo/campaigns/new'),
              icon: const Icon(Icons.add),
              label: Text(t.newCampaign),
            )
          : null,
      body: RefreshIndicator(
        onRefresh: () async => invalidateCampaigns(ref, ngoId),
        child: ListView(padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, 96), children: [
          if (summary != null)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: Text(limit == null || limit < 0 ? t.liveCampaignsCount(live) : t.liveCampaignsOfLimit(live, '$limit'),
                  style: const TextStyle(color: AppColors.textMuted)),
            ),
          if ((m?.isAdmin ?? false) && pending > 0 && _status != CampaignStatus.pending)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: AppCard(
                color: const Color(0xFFFFF4E5),
                onTap: () => setState(() => _status = CampaignStatus.pending),
                child: Row(children: [
                  const Icon(Icons.how_to_reg, color: Color(0xFFB45309)),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: Text(t.campaignsAwaitingYou(pending), style: const TextStyle(fontWeight: FontWeight.w600))),
                  const Icon(Icons.chevron_right),
                ]),
              ),
            ),
          SizedBox(
            height: 44,
            child: ListView(scrollDirection: Axis.horizontal, children: [
              for (final f in filters)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(label: Text(f.$2), selected: _status == f.$1, onSelected: (_) => setState(() => _status = f.$1)),
                ),
            ]),
          ),
          const SizedBox(height: AppSpacing.sm),
          ...list.when<List<Widget>>(
            loading: () => [const SizedBox(height: 200, child: LoadingView())],
            error: (e, _) {
              final err = ApiError.from(e);
              final msg = (err.status == 404 || err.status == 503 || err.isNetwork) ? t.serviceUnavailable : err.message;
              return [ErrorState(message: msg, onRetry: () => ref.invalidate(ngoCampaignsProvider(filter)))];
            },
            data: (items) => items.isEmpty
                ? [EmptyState(icon: Icons.campaign_outlined, title: t.campaignsEmpty, message: canCreate ? t.campaignsEmptyHint : null)]
                : [
                    for (final c in items)
                      Padding(padding: const EdgeInsets.only(bottom: AppSpacing.sm), child: _TeamCampaignCard(campaign: c, ngoId: ngoId)),
                  ],
          ),
        ]),
      ),
    );
  }
}

class _TeamCampaignCard extends StatelessWidget {
  const _TeamCampaignCard({required this.campaign, required this.ngoId});
  final Map<String, dynamic> campaign;
  final String ngoId;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final c = campaign;
    final status = '${c['status']}';
    final changes = status == CampaignStatus.draft && c['rejectionReason'] != null;
    return AppCard(
      padding: const EdgeInsets.all(AppSpacing.sm),
      onTap: () => context.push('/ngo/campaigns/${c['id']}'),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 112, child: CampaignCover(campaign: c, ngoId: ngoId, small: true, radius: 12)),
        const SizedBox(width: AppSpacing.sm),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${c['title']}', maxLines: 2, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Wrap(spacing: 6, runSpacing: 4, children: [
            _StatusChip(status),
            if (changes) StatusBadge(t.changesRequested, tone: BadgeTone.error),
          ]),
          const SizedBox(height: 6),
          CampaignProgress(campaign: c, compact: true),
        ])),
      ]),
    );
  }
}

// ============================================================ NGO: detail + workflow (CAMP-003/005/006/007/008/011/012)

class CampaignDetailPage extends ConsumerWidget {
  const CampaignDetailPage({super.key, required this.campaignId});
  final String campaignId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(ngoCampaignProvider((ngoId, campaignId)));
    return Scaffold(
      appBar: AppBar(title: Text(t.campaignDetails)),
      body: RefreshIndicator(
        onRefresh: () async => invalidateCampaigns(ref, ngoId, campaignId),
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [
            ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(ngoCampaignProvider((ngoId, campaignId)))),
          ]),
          data: (c) => _CampaignDetailBody(campaign: c, ngoId: ngoId),
        ),
      ),
    );
  }
}

class _CampaignDetailBody extends ConsumerWidget {
  const _CampaignDetailBody({required this.campaign, required this.ngoId});
  final Map<String, dynamic> campaign;
  final String ngoId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final c = campaign;
    final id = '${c['id']}';
    final status = '${c['status']}';
    final m = ref.watch(currentMembershipProvider);
    final me = ref.watch(meProvider).value;
    final admin = m?.isAdmin ?? false;
    final canManage = m?.can('CAMPAIGN_MANAGE') ?? false;
    final canSeeDonations = m?.can('DONATION_VIEW') ?? false;
    final names = ref.watch(teamNamesProvider(ngoId)).value ?? const {};
    final testDonations = ref.watch(campaignSummaryProvider(ngoId)).value?['testDonations'] == true;
    String who(Object? userId) =>
        userId == null ? t.aTeamMember : (userId == me?.userId ? t.you : names['$userId'] ?? t.aTeamMember);

    final canEdit = canManage && (status == CampaignStatus.draft || status == CampaignStatus.pending || (status == CampaignStatus.live && admin));

    Future<void> run(Future<void> Function() action, String done) async {
      try {
        await action();
        invalidateCampaigns(ref, ngoId, id);
        if (context.mounted) showSnack(context, done);
      } catch (e) {
        if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
      }
    }

    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/campaigns/$id';

    final actions = <Widget>[];
    if (status == CampaignStatus.draft && canManage) {
      if (admin) {
        actions.add(BusyButton(label: t.publishNow, icon: Icons.public, onPressed: () async {
          if (!await confirm(context, title: t.publishNow, message: t.approveConfirm, confirmLabel: t.publishNow, cancelLabel: t.cancel)) return;
          await run(() => api.post('$base/approve', body: {'version': c['version']}), t.campaignPublished);
        }));
      } else {
        actions.add(BusyButton(label: t.sendForApproval, icon: Icons.send, onPressed: () async {
          if (!await confirm(context, title: t.sendForApproval, message: t.sendForApprovalConfirm, confirmLabel: t.send, cancelLabel: t.cancel)) return;
          await run(() => api.post('$base/submit'), t.sentForApproval);
        }));
      }
    }
    if (status == CampaignStatus.pending && admin) {
      actions.add(BusyButton(label: t.approvePublish, icon: Icons.check_circle, onPressed: () async {
        if (!await confirm(context, title: t.approvePublish, message: t.approveConfirm, confirmLabel: t.approve, cancelLabel: t.cancel)) return;
        await run(() => api.post('$base/approve', body: {'version': c['version']}), t.campaignPublished);
      }));
      actions.add(BusyButton(label: t.requestChanges, icon: Icons.edit_note, outlined: true, onPressed: () async {
        final reason = await _askReason(context);
        if (reason == null) return;
        await run(() => api.post('$base/reject', body: {'reason': reason}), t.changesSent);
      }));
    }
    if (status == CampaignStatus.pending && !admin && canManage) {
      actions.add(BusyButton(label: t.withdrawRequest, icon: Icons.undo, outlined: true,
          onPressed: () => run(() => api.post('$base/withdraw'), t.movedToDrafts)));
    }
    if (status == CampaignStatus.live && testDonations && (m?.can('DONATION_RECORD') ?? false)) {
      actions.add(BusyButton(label: t.addTestDonation, icon: Icons.science_outlined, outlined: true, onPressed: () async {
        final r = await showModalBottomSheet<(String, String)>(
            context: context, isScrollControlled: true, showDragHandle: true, builder: (_) => const _TestDonationSheet());
        if (r == null) return;
        await run(() => api.post('$base/test-donations', body: {'amount': r.$1, if (r.$2.isNotEmpty) 'donorLabel': r.$2}),
            t.testDonationAdded);
      }));
    }
    if (status == CampaignStatus.live && admin) {
      actions.add(BusyButton(label: t.closeCampaign, icon: Icons.lock_outline, outlined: true, onPressed: () async {
        final ok = await confirm(context, title: t.closeCampaign, message: t.closeConfirm, confirmLabel: t.closeCampaign,
            cancelLabel: t.cancel, destructive: true);
        if (!ok) return;
        await run(() => api.post('$base/close'), t.campaignClosed);
      }));
    }
    if ((status == CampaignStatus.draft && canManage) || (status == CampaignStatus.closed && admin)) {
      final draft = status == CampaignStatus.draft;
      actions.add(TextButton.icon(
        style: TextButton.styleFrom(foregroundColor: AppColors.error, minimumSize: const Size.fromHeight(kMinTouchTarget)),
        icon: const Icon(Icons.archive_outlined),
        label: Text(draft ? t.discardDraft : t.archiveCampaign),
        onPressed: () async {
          final ok = await confirm(context, title: draft ? t.discardDraft : t.archiveCampaign, message: t.archiveConfirm,
              confirmLabel: draft ? t.discardDraft : t.archiveCampaign, cancelLabel: t.cancel, destructive: true);
          if (!ok) return;
          try {
            await api.post('$base/archive');
            invalidateCampaigns(ref, ngoId, id);
            if (context.mounted) {
              showSnack(context, t.campaignArchived);
              context.pop();
            }
          } catch (e) {
            if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
          }
        },
      ));
    }

    return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
      CampaignGallery(campaign: c, ngoId: ngoId),
      const SizedBox(height: AppSpacing.md),
      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(child: Text('${c['title']}', style: Theme.of(context).textTheme.titleLarge)),
        const SizedBox(width: AppSpacing.sm),
        _StatusChip(status),
      ]),
      const SizedBox(height: 4),
      Text('${c['summary']}', style: const TextStyle(color: AppColors.textMuted, fontSize: 15)),
      const SizedBox(height: AppSpacing.md),
      // Workflow state explained in plain words.
      if (status == CampaignStatus.draft && c['rejectionReason'] != null)
        _Notice(icon: Icons.feedback_outlined, color: AppColors.error, title: t.changesRequested, body: '${c['rejectionReason']}'),
      if (status == CampaignStatus.pending)
        _Notice(icon: Icons.hourglass_top, color: const Color(0xFFB45309), title: t.campaignStatusLabel(status),
            body: admin ? t.awaitingApprovalAdmin : t.awaitingApprovalTeam),
      if (status == CampaignStatus.live && !admin && canManage)
        _Notice(icon: Icons.info_outline, color: AppColors.growth, title: t.campaignStatusLabel(status), body: t.liveEditAdminOnly),
      AppCard(child: CampaignProgress(campaign: c)),
      if (c['shareUrl'] != null) ...[
        const SizedBox(height: AppSpacing.sm),
        CampaignShareRow(campaign: c),
      ],
      const SizedBox(height: AppSpacing.md),
      AppCard(child: Column(children: [
        KeyValueRow(t.campaignCause, c['cause'] == null ? '—' : causeLabel('${c['cause']}')),
        KeyValueRow(t.campaignStart, _day(c['startDate'])),
        KeyValueRow(t.campaignEnd, _day(c['endDate'])),
        KeyValueRow(t.createdByLabel, '${who(c['createdBy'])} · ${formatDate(c['createdAt'])}'),
        if (c['submittedAt'] != null)
          KeyValueRow(t.sentForApprovalLabel, '${who(c['submittedBy'])} · ${formatDate(c['submittedAt'])}'),
        if (c['publishedAt'] != null)
          KeyValueRow(t.approvedByLabel, '${who(c['approvedBy'])} · ${formatDate(c['publishedAt'])}'),
      ])),
      if ((c['story'] as String?)?.isNotEmpty ?? false) ...[
        const SizedBox(height: AppSpacing.md),
        SectionHeader(t.aboutCampaign),
        Text('${c['story']}', style: const TextStyle(fontSize: 15, height: 1.5)),
      ],
      const SizedBox(height: AppSpacing.lg),
      if (canEdit)
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm),
          child: OutlinedButton.icon(
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: () => context.push('/ngo/campaigns/$id/edit'),
            icon: const Icon(Icons.edit_outlined),
            label: Text(t.editCampaign),
          ),
        ),
      for (final a in actions) Padding(padding: const EdgeInsets.only(bottom: AppSpacing.sm), child: a),
      if (canSeeDonations && (status == CampaignStatus.live || status == CampaignStatus.closed)) ...[
        const SizedBox(height: AppSpacing.md),
        SectionHeader(t.campaignDonations),
        _DonationsList(ngoId: ngoId, campaignId: id),
      ],
    ]);
  }

  static Future<String?> _askReason(BuildContext context) {
    final t = L10n.of(context);
    final ctl = TextEditingController();
    final key = GlobalKey<FormState>();
    return showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(t.requestChanges),
        content: Form(
          key: key,
          child: TextFormField(
            controller: ctl,
            autofocus: true,
            maxLines: 4,
            maxLength: 500,
            decoration: InputDecoration(hintText: t.requestChangesHint),
            validator: V(t).text(min: 5, max: 500),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c), child: Text(t.cancel)),
          FilledButton(
            onPressed: () {
              if (key.currentState!.validate()) Navigator.pop(c, ctl.text.trim());
            },
            child: Text(t.send),
          ),
        ],
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.icon, required this.color, required this.title, required this.body});
  final IconData icon;
  final Color color;
  final String title;
  final String body;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: AppSpacing.md),
        child: AppCard(
          color: color.withValues(alpha: 0.08),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Icon(icon, color: color),
            const SizedBox(width: AppSpacing.sm),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: TextStyle(fontWeight: FontWeight.w700, color: color)),
              const SizedBox(height: 2),
              Text(body),
            ])),
          ]),
        ),
      );
}

class _DonationsList extends ConsumerWidget {
  const _DonationsList({required this.ngoId, required this.campaignId});
  final String ngoId;
  final String campaignId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(campaignDonationsProvider((ngoId, campaignId)));
    return data.when(
      loading: () => const Padding(padding: EdgeInsets.all(AppSpacing.md), child: Center(child: CircularProgressIndicator())),
      error: (e, _) => Text(ApiError.from(e).message, style: const TextStyle(color: AppColors.error)),
      data: (list) => list.isEmpty
          ? Text(t.noDonationsYet, style: const TextStyle(color: AppColors.textMuted))
          : AppCard(
              padding: EdgeInsets.zero,
              child: Column(children: [
                for (final d in list)
                  ListTile(
                    leading: const CircleAvatar(backgroundColor: AppColors.primarySoft, child: Icon(Icons.favorite, color: AppColors.primary, size: 20)),
                    title: Text((d['donorLabel'] as String?)?.isNotEmpty ?? false ? '${d['donorLabel']}' : t.anonymousDonor),
                    subtitle: Text(formatDate(d['createdAt'])),
                    trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                      Text(formatInr(d['amount']), style: const TextStyle(fontWeight: FontWeight.w700)),
                      if (d['test'] == true) Text(t.testLabel, style: const TextStyle(fontSize: 11, color: AppColors.warning, fontWeight: FontWeight.w700)),
                    ]),
                  ),
              ]),
            ),
    );
  }
}

class _TestDonationSheet extends StatefulWidget {
  const _TestDonationSheet();
  @override
  State<_TestDonationSheet> createState() => _TestDonationSheetState();
}

class _TestDonationSheetState extends State<_TestDonationSheet> {
  final _key = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _donor = TextEditingController();

  @override
  void dispose() {
    _amount.dispose();
    _donor.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Padding(
      padding: EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg, MediaQuery.viewInsetsOf(context).bottom + AppSpacing.lg),
      child: Form(
        key: _key,
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(t.addTestDonation, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(t.testDonationNote, style: const TextStyle(color: AppColors.textMuted)),
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _amount,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))],
            decoration: InputDecoration(labelText: t.amountRupees, prefixText: '₹ '),
            validator: V(t).amount(min: 10, max: 1000000),
          ),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(
            controller: _donor,
            maxLength: 80,
            decoration: InputDecoration(labelText: t.donorNameOptional, counterText: ''),
          ),
          const SizedBox(height: AppSpacing.md),
          FilledButton(
            onPressed: () {
              if (_key.currentState!.validate()) Navigator.pop(context, (_amount.text.trim(), _donor.text.trim()));
            },
            child: Text(t.addTestDonation),
          ),
        ]),
      ),
    );
  }
}

// ============================================================ NGO: create / edit (CAMP-002/004)

class CampaignFormPage extends ConsumerStatefulWidget {
  const CampaignFormPage({super.key, this.campaignId});
  final String? campaignId;
  @override
  ConsumerState<CampaignFormPage> createState() => _CampaignFormPageState();
}

class _CampaignFormPageState extends ConsumerState<CampaignFormPage> {
  final _key = GlobalKey<FormState>();
  final _title = TextEditingController();
  final _summary = TextEditingController();
  final _story = TextEditingController();
  final _goal = TextEditingController();
  String? _cause;
  DateTime? _start;
  DateTime? _end;
  /// Gallery as it will be after saving: first item = cover. Existing photos have an id, new ones bytes.
  final List<_PhotoItem> _photos = [];
  final Set<String> _removed = {};
  bool _loaded = false;
  bool _saving = false;
  String? _dateError;
  Map<String, dynamic>? _existing;

  bool get _editing => widget.campaignId != null;

  @override
  void initState() {
    super.initState();
    if (!_editing) {
      _start = today();
      _end = today().add(const Duration(days: 30));
      _loaded = true;
    }
  }

  @override
  void dispose() {
    for (final c in [_title, _summary, _story, _goal]) {
      c.dispose();
    }
    super.dispose();
  }

  void _fill(Map<String, dynamic> c) {
    _existing = c;
    _title.text = '${c['title'] ?? ''}';
    _summary.text = '${c['summary'] ?? ''}';
    _story.text = '${c['story'] ?? ''}';
    final goal = num.tryParse('${c['goalAmount'] ?? ''}');
    _goal.text = goal == null ? '' : (goal == goal.roundToDouble() ? goal.toInt().toString() : goal.toStringAsFixed(2));
    _cause = c['cause'] as String?;
    _start = parseDay(c['startDate']);
    _end = parseDay(c['endDate']);
    _photos..clear()..addAll([for (final id in (c['photoIds'] as List? ?? const [])) _PhotoItem(id: '$id')]);
    _loaded = true;
  }

  Future<void> _pickDate(bool start) async {
    final now = today();
    final live = _existing?['status'] == CampaignStatus.live;
    if (start && live) return; // a live campaign's start date is fixed
    final initial = (start ? _start : _end) ?? now;
    final first = start ? (initial.isBefore(now) ? initial : now) : (_start ?? now);
    final picked = await showDatePicker(
      context: context,
      initialDate: initial.isBefore(first) ? first : initial,
      firstDate: first,
      lastDate: now.add(const Duration(days: 730)),
    );
    if (picked == null) return;
    setState(() {
      _dateError = null;
      if (start) {
        _start = picked;
        if (_end != null && _end!.isBefore(picked)) _end = picked.add(const Duration(days: 30));
      } else {
        _end = picked;
      }
    });
  }

  Future<void> _addPhotos() async {
    final room = maxCampaignPhotos - _photos.length;
    if (room <= 0) return;
    final files = await ImagePicker().pickMultiImage(maxWidth: 2400, maxHeight: 2400, imageQuality: 90, limit: room > 1 ? room : null);
    if (files.isEmpty) return;
    final picked = <_PhotoItem>[];
    for (final f in files.take(room)) {
      picked.add(_PhotoItem(bytes: await f.readAsBytes()));
    }
    if (mounted) setState(() => _photos.addAll(picked));
  }

  void _photoAction(int i, String action) => setState(() {
        final p = _photos.removeAt(i);
        if (action == 'cover') {
          _photos.insert(0, p);
        } else if (p.id != null) {
          _removed.add(p.id!);
        }
      });

  /// Applies gallery changes after the campaign itself is saved: removals, uploads (in order), then the cover.
  Future<void> _savePhotos(String ngoId, String id) async {
    final api = ref.read(apiProvider);
    final base = '/api/v1/ngos/$ngoId/campaigns/$id/photos';
    for (final r in _removed) {
      await api.delete('$base/$r');
    }
    String? coverId = _photos.isEmpty ? null : _photos.first.id;
    for (var i = 0; i < _photos.length; i++) {
      final p = _photos[i];
      if (p.bytes == null) continue;
      final r = await api.upload(base, bytes: p.bytes!, filename: 'photo-${i + 1}.jpg');
      final ids = (r['photoIds'] as List? ?? const []).map((e) => '$e').toList();
      if (i == 0 && ids.isNotEmpty) coverId = ids.last; // new photos are appended
    }
    if (coverId != null) {
      final current = await api.getJson('/api/v1/ngos/$ngoId/campaigns/$id');
      final ids = (current['photoIds'] as List? ?? const []).map((e) => '$e').toList();
      if (ids.isNotEmpty && ids.first != coverId) await api.put('$base/$coverId/cover');
    }
  }

  String? _validateDates(L10n t) {
    if (_start == null || _end == null) return t.required;
    if (_end!.isBefore(_start!)) return t.endBeforeStart;
    if (_end!.isBefore(today())) return t.endInPast;
    if (_end!.difference(_start!).inDays > 366) return t.campaignTooLong;
    return null;
  }

  Future<void> _save() async {
    final t = L10n.of(context);
    final ngoId = ref.read(currentNgoIdProvider);
    final dateError = _validateDates(t);
    setState(() => _dateError = dateError);
    if (!_key.currentState!.validate() || dateError != null || ngoId == null) return;
    setState(() => _saving = true);
    final api = ref.read(apiProvider);
    final body = {
      'title': _title.text.trim(),
      'summary': _summary.text.trim(),
      'story': _story.text.trim().isEmpty ? null : _story.text.trim(),
      'cause': _cause,
      'goalAmount': _goal.text.trim(),
      'startDate': isoDay(_start!),
      'endDate': isoDay(_end!),
      if (_editing) 'version': _existing?['version'],
    };
    try {
      final saved = _editing
          ? await api.put('/api/v1/ngos/$ngoId/campaigns/${widget.campaignId}', body: body)
          : await api.post('/api/v1/ngos/$ngoId/campaigns', body: body);
      final id = '${saved['id']}';
      try {
        await _savePhotos(ngoId, id);
      } catch (e) {
        if (mounted) showSnack(context, '${t.coverNotSaved}: ${ApiError.from(e).message}', error: true);
      }
      invalidateCampaigns(ref, ngoId, id);
      if (!mounted) return;
      showSnack(context, _editing ? t.saved : t.draftSaved);
      if (_editing) {
        context.pop();
      } else {
        context.pushReplacement('/ngo/campaigns/$id');
      }
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    if (_editing && !_loaded) {
      final data = ref.watch(ngoCampaignProvider((ngoId ?? '', widget.campaignId!)));
      return Scaffold(
        appBar: AppBar(title: Text(t.editCampaign)),
        body: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message),
          data: (c) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted && !_loaded) setState(() => _fill(c));
            });
            return const LoadingView();
          },
        ),
      );
    }
    final v = V(t);
    final live = _existing?['status'] == CampaignStatus.live;
    return Scaffold(
      appBar: AppBar(title: Text(_editing ? t.editCampaign : t.newCampaign)),
      body: Form(
        key: _key,
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          // Photo gallery (max 5, first = cover)
          Row(children: [
            Expanded(child: Text(t.campaignPhotos, style: Theme.of(context).textTheme.titleSmall)),
            Text('${_photos.length}/$maxCampaignPhotos', style: const TextStyle(color: AppColors.textMuted)),
          ]),
          const SizedBox(height: AppSpacing.sm),
          SizedBox(
            height: 96,
            child: ListView(scrollDirection: Axis.horizontal, children: [
              for (var i = 0; i < _photos.length; i++)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: _PhotoThumb(
                    item: _photos[i],
                    ngoId: ngoId ?? '',
                    campaignId: widget.campaignId,
                    isCover: i == 0,
                    onAction: _saving ? null : (a) => _photoAction(i, a),
                  ),
                ),
              if (_photos.length < maxCampaignPhotos)
                InkWell(
                  borderRadius: BorderRadius.circular(12),
                  onTap: _saving ? null : _addPhotos,
                  child: Container(
                    width: 150,
                    decoration: BoxDecoration(
                      color: AppColors.primarySoft,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppColors.primary.withValues(alpha: 0.3)),
                    ),
                    child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                      const Icon(Icons.add_photo_alternate_outlined, color: AppColors.primary),
                      const SizedBox(height: 4),
                      Text(t.addPhotos, style: const TextStyle(color: AppColors.primary, fontWeight: FontWeight.w600)),
                    ]),
                  ),
                ),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.only(top: 6, bottom: AppSpacing.md),
            child: Text(t.campaignPhotosHelp, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
          ),
          TextFormField(
            controller: _title,
            maxLength: 120,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(labelText: t.campaignTitle, hintText: t.campaignTitleHint),
            validator: v.text(min: 5, max: 120),
          ),
          const SizedBox(height: AppSpacing.sm),
          TextFormField(
            controller: _summary,
            maxLength: 300,
            maxLines: 2,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(labelText: t.campaignSummary, hintText: t.campaignSummaryHint),
            validator: v.text(min: 10, max: 300),
          ),
          const SizedBox(height: AppSpacing.sm),
          DropdownButtonFormField<String>(
            initialValue: _cause,
            isExpanded: true,
            decoration: InputDecoration(labelText: t.campaignCause),
            items: [for (final c in campaignCauses) DropdownMenuItem(value: c, child: Text(causeLabel(c)))],
            onChanged: (x) => setState(() => _cause = x),
            validator: (x) => x == null ? t.required : null,
          ),
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _goal,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))],
            decoration: InputDecoration(labelText: t.campaignGoal, prefixText: '₹ ', helperText: t.campaignGoalHelp),
            validator: v.amount(min: 1000, max: 100000000),
          ),
          const SizedBox(height: AppSpacing.md),
          Row(children: [
            Expanded(child: _DateField(label: t.campaignStart, value: _start, locked: live, onTap: () => _pickDate(true))),
            const SizedBox(width: AppSpacing.sm),
            Expanded(child: _DateField(label: t.campaignEnd, value: _end, onTap: () => _pickDate(false))),
          ]),
          Padding(
            padding: const EdgeInsets.only(top: 4, left: 12),
            child: Text(_dateError ?? t.campaignDatesHelp,
                style: TextStyle(fontSize: 12, color: _dateError != null ? AppColors.error : AppColors.textMuted)),
          ),
          const SizedBox(height: AppSpacing.md),
          TextFormField(
            controller: _story,
            maxLength: 5000,
            minLines: 4,
            maxLines: 12,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(labelText: t.campaignStory, hintText: t.campaignStoryHint, alignLabelWithHint: true),
          ),
          const SizedBox(height: AppSpacing.md),
          if (!_editing)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: Text((ref.watch(currentMembershipProvider)?.isAdmin ?? false) ? t.newCampaignAdminNote : t.newCampaignTeamNote,
                  style: const TextStyle(color: AppColors.textMuted)),
            ),
          FilledButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white))
                : Text(_editing ? t.save : t.saveDraft),
          ),
        ]),
      ),
    );
  }
}

const maxCampaignPhotos = 5;

class _PhotoItem {
  _PhotoItem({this.id, this.bytes});
  final String? id;
  final Uint8List? bytes;
}

class _PhotoThumb extends ConsumerWidget {
  const _PhotoThumb({required this.item, required this.ngoId, required this.campaignId, required this.isCover, required this.onAction});
  final _PhotoItem item;
  final String ngoId;
  final String? campaignId;
  final bool isCover;
  final void Function(String)? onAction;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    Widget img;
    if (item.bytes != null) {
      img = Image.memory(item.bytes!, fit: BoxFit.cover);
    } else {
      final b = ref.watch(teamPhotoProvider((ngoId, campaignId ?? '', item.id!, 'small')));
      img = b.when(data: (x) => Image.memory(x, fit: BoxFit.cover),
          loading: () => const ColoredBox(color: AppColors.primarySoft), error: (_, __) => const ColoredBox(color: AppColors.primarySoft));
    }
    return SizedBox(
      width: 150,
      child: Stack(fit: StackFit.expand, children: [
        ClipRRect(borderRadius: BorderRadius.circular(12), child: img),
        if (isCover)
          Positioned(left: 6, top: 6, child: StatusBadge(t.coverLabel, color: AppColors.primary)),
        Positioned(
          right: 0,
          top: 0,
          child: PopupMenuButton<String>(
            enabled: onAction != null,
            icon: const CircleAvatar(radius: 14, backgroundColor: Colors.black54, child: Icon(Icons.more_vert, size: 18, color: Colors.white)),
            onSelected: onAction,
            itemBuilder: (_) => [
              if (!isCover) PopupMenuItem(value: 'cover', child: Text(t.makeCover)),
              PopupMenuItem(value: 'remove', child: Text(t.removePhoto)),
            ],
          ),
        ),
      ]),
    );
  }
}

class _DateField extends StatelessWidget {
  const _DateField({required this.label, required this.value, required this.onTap, this.locked = false});
  final String label;
  final DateTime? value;
  final VoidCallback onTap;
  final bool locked;
  @override
  Widget build(BuildContext context) => InkWell(
        onTap: locked ? null : onTap,
        borderRadius: BorderRadius.circular(AppRadius.button),
        child: InputDecorator(
          decoration: InputDecoration(labelText: label, suffixIcon: Icon(locked ? Icons.lock_outline : Icons.event, size: 20)),
          child: Text(value == null ? '—' : DateFormat('d MMM yyyy').format(value!)),
        ),
      );
}

// ============================================================ Donor: discover + details (DAPP-007/008)

/// Horizontal strip of live campaigns for the donor home.
class LiveCampaignsStrip extends ConsumerWidget {
  const LiveCampaignsStrip({super.key, this.ngoId, this.title});
  final String? ngoId;
  final String? title;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(publicCampaignsProvider(PublicCampaignQuery(ngoId: ngoId, size: 10)));
    final list = data.value ?? const [];
    if (list.isEmpty) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text(title ?? t.liveCampaigns, style: Theme.of(context).textTheme.titleMedium)),
        if (ngoId == null) TextButton(onPressed: () => context.push('/donor/campaigns'), child: Text(t.seeAll)),
      ]),
      const SizedBox(height: AppSpacing.xs),
      SizedBox(
        height: 300,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: list.length,
          separatorBuilder: (_, __) => const SizedBox(width: AppSpacing.sm),
          itemBuilder: (_, i) => SizedBox(width: 270, child: PublicCampaignCard(campaign: list[i])),
        ),
      ),
    ]);
  }
}

class PublicCampaignCard extends StatelessWidget {
  const PublicCampaignCard({super.key, required this.campaign});
  final Map<String, dynamic> campaign;
  @override
  Widget build(BuildContext context) {
    final c = campaign;
    return AppCard(
      padding: EdgeInsets.zero,
      onTap: () => context.push('/donor/campaign/${c['id']}'),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
        CampaignCover(campaign: c, small: true, radius: AppRadius.card),
        Padding(
          padding: const EdgeInsets.all(AppSpacing.sm),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${c['title']}', maxLines: 2, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 2),
            Text([c['ngoName'], c['ngoCity']].where((e) => e != null).join(' · '),
                maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
            const SizedBox(height: 8),
            CampaignProgress(campaign: c, compact: true),
          ]),
        ),
      ]),
    );
  }
}

class PublicCampaignsPage extends ConsumerStatefulWidget {
  const PublicCampaignsPage({super.key});
  @override
  ConsumerState<PublicCampaignsPage> createState() => _PublicCampaignsPageState();
}

class _PublicCampaignsPageState extends ConsumerState<PublicCampaignsPage> {
  String _q = '';
  String? _cause;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final query = PublicCampaignQuery(cause: _cause, q: _q, size: 50);
    final data = ref.watch(publicCampaignsProvider(query));
    return Scaffold(
      appBar: AppBar(title: Text(t.liveCampaigns)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(publicCampaignsProvider(query)),
        child: ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
          TextField(
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(hintText: t.searchCampaigns, prefixIcon: const Icon(Icons.search)),
            onSubmitted: (v) => setState(() => _q = v.trim()),
          ),
          const SizedBox(height: AppSpacing.sm),
          SizedBox(
            height: 44,
            child: ListView(scrollDirection: Axis.horizontal, children: [
              for (final c in campaignCauses.take(10))
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: FilterChip(label: Text(causeLabel(c)), selected: _cause == c,
                      onSelected: (s) => setState(() => _cause = s ? c : null)),
                ),
            ]),
          ),
          const SizedBox(height: AppSpacing.sm),
          ...data.when<List<Widget>>(
            loading: () => [const SizedBox(height: 200, child: LoadingView())],
            error: (e, _) => [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(publicCampaignsProvider(query)))],
            data: (list) => list.isEmpty
                ? [EmptyState(icon: Icons.campaign_outlined, title: t.noLiveCampaigns)]
                : [for (final c in list) Padding(padding: const EdgeInsets.only(bottom: AppSpacing.md), child: PublicCampaignCard(campaign: c))],
          ),
        ]),
      ),
    );
  }
}

class PublicCampaignPage extends ConsumerWidget {
  const PublicCampaignPage({super.key, required this.campaignId});
  final String campaignId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(publicCampaignProvider(campaignId));
    return Scaffold(
      appBar: AppBar(),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(publicCampaignProvider(campaignId))),
        data: (c) {
          final phase = '${c['phase']}';
          final team = isTeamMemberOf(ref.watch(meProvider).value, c['ngoId']);
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            CampaignGallery(campaign: c),
            const SizedBox(height: AppSpacing.md),
            Text('${c['title']}', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            InkWell(
              onTap: () => context.push('/donor/ngo/${c['ngoId']}'),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Row(children: [
                  const Icon(Icons.verified, color: AppColors.primary, size: 18),
                  const SizedBox(width: 6),
                  Expanded(child: Text(t.byNgo('${c['ngoName'] ?? ''}'),
                      style: const TextStyle(color: AppColors.primary, fontWeight: FontWeight.w600))),
                  const Icon(Icons.chevron_right, color: AppColors.textMuted),
                ]),
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            AppCard(child: CampaignProgress(campaign: c)),
            const SizedBox(height: AppSpacing.md),
            Text('${c['summary']}', style: const TextStyle(fontSize: 16, height: 1.5, fontWeight: FontWeight.w500)),
            if ((c['story'] as String?)?.isNotEmpty ?? false) ...[
              const SizedBox(height: AppSpacing.md),
              SectionHeader(t.aboutCampaign),
              Text('${c['story']}', style: const TextStyle(fontSize: 15, height: 1.6)),
            ],
            if (c['cause'] != null) ...[
              const SizedBox(height: AppSpacing.md),
              Wrap(children: [StatusBadge(causeLabel('${c['cause']}'), tone: BadgeTone.info)]),
            ],
            const SizedBox(height: AppSpacing.xl),
            if (phase == 'ENDED')
              _Notice(icon: Icons.event_busy, color: AppColors.textMuted, title: t.campaignEnded, body: t.campaignEndedHint)
            else if (team)
              // Owner rule (2026-10-29): the NGO's own admins/staff don't donate here; they spread the word.
              _Notice(icon: Icons.groups_outlined, color: AppColors.primary, title: t.yourTeamCampaign,
                  body: t.yourTeamCampaignHint('${c['ngoName'] ?? ''}'))
            else
              FilledButton.icon(
                // Online checkout arrives with donation-payment-service.
                onPressed: phase == 'LIVE' ? () => showSnack(context, t.donateComingSoon) : null,
                icon: const Icon(Icons.volunteer_activism),
                label: Text(phase == 'UPCOMING' ? t.startsOn(_day(c['startDate'])) : t.donate),
              ),
            const SizedBox(height: AppSpacing.sm),
            CampaignShareRow(campaign: c),
          ]);
        },
      ),
    );
  }
}
