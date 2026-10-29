import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/config/app_config.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../ngo/ngo_api.dart';

String _level(L10n t, String? v) => switch (v) {
      'BASIC' => t.basic,
      'STANDARD' => t.standard,
      'ADVANCED' => t.advanced,
      'LIMITED' => t.basic,
      _ => t.no,
    };

String _limit(L10n t, String? v) => v == '-1' ? t.unlimited : (v ?? '0');

/// SUB-001/010/036-039: subscription overview.
class SubscriptionPage extends ConsumerWidget {
  const SubscriptionPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final sub = ref.watch(subscriptionProvider);
    final m = ref.watch(currentMembershipProvider);
    final canManage = m?.can('SUBSCRIPTION_MANAGE') ?? false;
    return Scaffold(
      appBar: AppBar(title: Text(t.subscription)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(subscriptionProvider),
        child: sub.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(subscriptionProvider)),
          data: (s) {
            if (s == null) return ListView(children: [EmptyState(icon: Icons.hourglass_top, title: t.notAvailableYet)]);
            final e = Map<String, dynamic>.from(s['entitlements'] as Map);
            final status = '${s['status']}';
            final color = AppColors.plan(s['plan'] as String?);
            final readOnly = s['operationsAllowed'] != true;
            return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              if (readOnly)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.md),
                  child: AppCard(color: AppColors.error.withValues(alpha: 0.08), child: Text(t.readOnlyNotice(status))),
                ),
              AppCard(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    CircleAvatar(backgroundColor: color.withValues(alpha: 0.12), child: Icon(Icons.workspace_premium, color: color)),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(child: Text('${s['planName']}', style: Theme.of(context).textTheme.titleLarge)),
                    StatusBadge(status, tone: StatusBadge.toneFor(status)),
                  ]),
                  const Divider(height: 24),
                  KeyValueRow(t.billingCycle, s['cycle'] == 'TRIAL' ? t.trial : (s['cycle'] == 'ANNUAL' ? t.annual : t.quarterly)),
                  KeyValueRow(t.renewsOn(''), formatDate(s['periodEnd'])),
                  if (s['graceEndsAt'] != null) KeyValueRow('Grace', formatDate(s['graceEndsAt'])),
                  KeyValueRow(t.staffLimit, _limit(t, e['STAFF_MAX'] as String?)),
                  KeyValueRow(t.activeCampaigns, _limit(t, e['CAMPAIGN_ACTIVE_MAX'] as String?)),
                  KeyValueRow(t.activeProjects, _limit(t, e['PROJECT_ACTIVE_MAX'] as String?)),
                  KeyValueRow(t.receiptDesigner, e['RECEIPT_ADVANCED_DESIGNER'] == 'true'
                      ? t.advanced : (e['RECEIPT_BASIC_DESIGNER'] == 'true' ? t.basic : t.no)),
                  KeyValueRow(t.navReports, _level(t, e['REPORTING_LEVEL'] as String?)),
                  KeyValueRow(t.prioritySupport, e['PRIORITY_SUPPORT'] == 'true' ? t.yes : t.no),
                ]),
              ),
              if (s['scheduledPlan'] != null)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.md),
                  child: AppCard(color: AppColors.warning.withValues(alpha: 0.10), child: Text(t.scheduledChange('${s['scheduledPlan']}'))),
                ),
              if (s['cancelAtPeriodEnd'] == true)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.md),
                  child: AppCard(color: AppColors.error.withValues(alpha: 0.08), child: Text(t.cancellationScheduled)),
                ),
              const SizedBox(height: AppSpacing.lg),
              FilledButton.icon(onPressed: () => context.push('/ngo/subscription/plans'), icon: const Icon(Icons.upgrade), label: Text(t.viewPlans)),
              const SizedBox(height: AppSpacing.sm),
              OutlinedButton.icon(onPressed: () => context.push('/ngo/subscription/billing'), icon: const Icon(Icons.receipt_long), label: Text(t.billingHistory)),
              if (canManage && status == 'ACTIVE') ...[
                const SizedBox(height: AppSpacing.sm),
                TextButton(
                  onPressed: () async {
                    final cancel = s['cancelAtPeriodEnd'] != true;
                    if (cancel && !await confirm(context, title: t.cancelSubscription, message: t.readOnlyNotice('CANCELLED'), destructive: true)) return;
                    try {
                      await ref.read(apiProvider).put('/api/v1/ngos/${s['ngoId']}/subscription/cancellation', body: {'cancel': cancel});
                      ref.invalidate(subscriptionProvider);
                    } catch (err) {
                      if (context.mounted) showSnack(context, ApiError.from(err).message, error: true);
                    }
                  },
                  child: Text(s['cancelAtPeriodEnd'] == true ? t.undoCancel : t.cancelSubscription,
                      style: TextStyle(color: s['cancelAtPeriodEnd'] == true ? AppColors.primary : AppColors.error)),
                ),
              ],
            ]);
          },
        ),
      ),
    );
  }
}

/// SUB-002..008, 012..019: plans, comparison and checkout.
class PlansPage extends ConsumerStatefulWidget {
  const PlansPage({super.key});
  @override
  ConsumerState<PlansPage> createState() => _PlansPageState();
}

class _PlansPageState extends ConsumerState<PlansPage> {
  String _cycle = 'QUARTERLY';
  int _blocks = 0;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final cat = ref.watch(planCatalogueProvider);
    final sub = ref.watch(subscriptionProvider).value;
    final canManage = ref.watch(currentMembershipProvider)?.can('SUBSCRIPTION_MANAGE') ?? false;
    return Scaffold(
      appBar: AppBar(title: Text(t.plans)),
      body: cat.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(planCatalogueProvider)),
        data: (c) {
          final plans = List<Map<String, dynamic>>.from((c['plans'] as List).map((e) => Map<String, dynamic>.from(e as Map)));
          final currentRank = plans.indexWhere((p) => p['code'] == sub?['plan']);
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            SegmentedButton<String>(
              segments: [
                ButtonSegment(value: 'QUARTERLY', label: Text(t.quarterly)),
                ButtonSegment(value: 'ANNUAL', label: Text('${t.annual} −${c['annualDiscountPercent']}%')),
              ],
              selected: {_cycle},
              onSelectionChanged: (s) => setState(() => _cycle = s.first),
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(t.pricesIncludeGst, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: AppSpacing.md),
            for (var i = 0; i < plans.length; i++)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.md),
                child: _PlanCard(
                  plan: plans[i],
                  cycle: _cycle,
                  isCurrent: plans[i]['code'] == sub?['plan'],
                  blocks: _blocks,
                  onBlocks: (b) => setState(() => _blocks = b),
                  action: !canManage || sub == null
                      ? null
                      : _actionFor(t, sub, plans[i], i, currentRank),
                ),
              ),
          ]);
        },
      ),
    );
  }

  (String, Future<void> Function())? _actionFor(L10n t, Map<String, dynamic> sub, Map<String, dynamic> plan, int rank, int currentRank) {
    final status = '${sub['status']}';
    final code = '${plan['code']}';
    final isCurrent = rank == currentRank;
    final ngoId = '${sub['ngoId']}';
    if (status == 'TRIAL' && !isCurrent) {
      return (t.selectOnTrial, () => _run(() => ref.read(apiProvider).put('/api/v1/ngos/$ngoId/subscription/trial-plan', body: {'plan': code})));
    }
    if (status == 'ACTIVE' && rank < currentRank) {
      return (t.downgradeAtRenewal, () => _downgrade(ngoId, code, sub));
    }
    if (status == 'ACTIVE' && rank > currentRank) {
      return (t.upgradeTo('${plan['name']}'), () => _checkout(ngoId, code));
    }
    if (isCurrent && status == 'ACTIVE' && plan['staffBlocksAllowed'] == true && _blocks > (sub['staffBlocks'] as num? ?? 0)) {
      return (t.addStaffBlocks, () => _checkout(ngoId, code));
    }
    if (status == 'ACTIVE' && isCurrent) {
      return (t.renew, () => _checkout(ngoId, code));
    }
    return (status == 'TRIAL' ? t.subscribe : t.renew, () => _checkout(ngoId, code));
  }

  Future<void> _run(Future<void> Function() f) async {
    try {
      await f();
      ref.invalidate(subscriptionProvider);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  Future<void> _downgrade(String ngoId, String plan, Map<String, dynamic> sub) async {
    final t = L10n.of(context);
    try {
      final impact = await ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/subscription/downgrade-impact', query: {'plan': plan});
      if (!mounted) return;
      final lost = List<String>.from(impact['featuresLost'] as List? ?? const []);
      final ok = await confirm(context, title: t.confirmDowngradeTitle,
          message: '${t.confirmDowngradeBody(formatDate(sub['periodEnd']))}\n\n${lost.map((e) => '• $e').join('\n')}');
      if (!ok) return;
      await _run(() => ref.read(apiProvider).post('/api/v1/ngos/$ngoId/subscription/downgrade',
          body: {'plan': plan, 'cycle': _cycle, 'confirmed': true}));
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  Future<void> _checkout(String ngoId, String plan) async {
    final api = ref.read(apiProvider);
    final body = {'plan': plan, 'cycle': _cycle, 'blocks': plan == 'ENTERPRISE' ? _blocks : 0};
    try {
      final quote = await api.post('/api/v1/ngos/$ngoId/subscription/quote', body: body);
      if (!mounted) return;
      final go = await showModalBottomSheet<bool>(
        context: context,
        isScrollControlled: true,
        builder: (_) => _QuoteSheet(quote: quote),
      );
      if (go != true) return;
      final checkout = await api.post('/api/v1/ngos/$ngoId/subscription/checkout', body: body, idempotencyKey: newIdempotencyKey());
      if (!mounted) return;
      await showModalBottomSheet<void>(
        context: context,
        isDismissible: false,
        builder: (_) => PaymentSheet(ngoId: ngoId, checkout: checkout),
      );
      ref.invalidate(subscriptionProvider);
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }
}

class _PlanCard extends StatelessWidget {
  const _PlanCard({required this.plan, required this.cycle, required this.isCurrent, required this.blocks, required this.onBlocks, this.action});
  final Map<String, dynamic> plan;
  final String cycle;
  final bool isCurrent;
  final int blocks;
  final void Function(int) onBlocks;
  final (String, Future<void> Function())? action;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final color = AppColors.plan(plan['code'] as String?);
    final e = Map<String, dynamic>.from(plan['entitlements'] as Map);
    final period = cycle == 'ANNUAL' ? plan['annualPrice'] : plan['quarterlyPrice'];
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppRadius.card),
        border: Border.all(color: isCurrent ? color : AppColors.outline, width: isCurrent ? 2 : 1),
      ),
      padding: const EdgeInsets.all(AppSpacing.md),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Container(width: 10, height: 10, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
          const SizedBox(width: 8),
          Expanded(child: Text('${plan['name']}', style: Theme.of(context).textTheme.titleLarge?.copyWith(color: color))),
          if (isCurrent) StatusBadge(t.currentPlan, color: color),
        ]),
        const SizedBox(height: 8),
        Text.rich(TextSpan(children: [
          TextSpan(text: formatInr(plan['monthlyEquivalent'], whole: true), style: Theme.of(context).textTheme.headlineSmall),
          TextSpan(text: t.perMonth, style: const TextStyle(color: AppColors.textMuted)),
        ])),
        Text(cycle == 'ANNUAL' ? t.billedAnnually(formatInr(period)) : t.billedQuarterly(formatInr(period)),
            style: const TextStyle(color: AppColors.textMuted)),
        const Divider(height: 24),
        _Feature(Icons.group, t.includesStaff((plan['includedStaff'] as num).toInt())),
        _Feature(Icons.campaign, '${t.activeCampaigns}: ${_limit(t, e['CAMPAIGN_ACTIVE_MAX'] as String?)}'),
        _Feature(Icons.receipt_long, '${t.receiptDesigner}: ${e['RECEIPT_ADVANCED_DESIGNER'] == 'true' ? t.advanced : (e['RECEIPT_BASIC_DESIGNER'] == 'true' ? t.basic : t.no)}'),
        _Feature(Icons.insights, '${t.navReports}: ${_level(t, e['REPORTING_LEVEL'] as String?)}'),
        if (e['PRIORITY_SUPPORT'] == 'true') _Feature(Icons.support_agent, t.prioritySupport),
        if (plan['staffBlocksAllowed'] == true) ...[
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: Text(t.addStaffBlocks)),
            IconButton(onPressed: blocks > 0 ? () => onBlocks(blocks - 1) : null, icon: const Icon(Icons.remove_circle_outline)),
            Text(t.blocks(blocks)),
            IconButton(onPressed: blocks < 100 ? () => onBlocks(blocks + 1) : null, icon: const Icon(Icons.add_circle_outline)),
          ]),
        ],
        if (action != null) ...[
          const SizedBox(height: AppSpacing.sm),
          BusyButton(label: action!.$1, onPressed: action!.$2),
        ],
      ]),
    );
  }
}

class _Feature extends StatelessWidget {
  const _Feature(this.icon, this.text);
  final IconData icon;
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(children: [Icon(icon, size: 18, color: AppColors.textMuted), const SizedBox(width: 8), Expanded(child: Text(text))]),
      );
}

class _QuoteSheet extends StatelessWidget {
  const _QuoteSheet({required this.quote});
  final Map<String, dynamic> quote;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final lines = List<Map<String, dynamic>>.from((quote['lines'] as List).map((e) => Map<String, dynamic>.from(e as Map)));
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(t.orderSummary, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: AppSpacing.md),
          for (final l in lines) KeyValueRow('${l['description']}', formatInr(l['amount'])),
          const Divider(),
          KeyValueRow(t.total, formatInr(quote['total'])),
          Text(t.gstIncluded(formatInr(quote['gst'])), style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
          const SizedBox(height: AppSpacing.lg),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(t.payNow(formatInr(quote['total'])))),
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(t.cancel)),
        ]),
      ),
    );
  }
}

/// SUB-013..016. In development the provider is simulated; the result still flows through the signed webhook.
class PaymentSheet extends ConsumerStatefulWidget {
  const PaymentSheet({super.key, required this.ngoId, required this.checkout});
  final String ngoId;
  final Map<String, dynamic> checkout;
  @override
  ConsumerState<PaymentSheet> createState() => _PaymentSheetState();
}

class _PaymentSheetState extends ConsumerState<PaymentSheet> {
  String? _result;

  Future<void> _simulate(String outcome) async {
    try {
      final r = await ref.read(apiProvider).post(
          '/api/v1/ngos/${widget.ngoId}/subscription/mock-checkout/${widget.checkout['orderId']}',
          body: {'outcome': outcome, 'duplicate': false});
      setState(() => _result = '${r['status']}');
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    if (_result != null) {
      final ok = _result == 'PAID';
      final pending = _result == 'PENDING';
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(ok ? Icons.check_circle : (pending ? Icons.hourglass_top : Icons.cancel), size: 64,
                color: ok ? AppColors.success : (pending ? AppColors.warning : AppColors.error)),
            const SizedBox(height: AppSpacing.md),
            Text(ok ? t.paymentSuccess : (pending ? t.paymentPending : t.paymentFailed), style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(onPressed: () => Navigator.pop(context), child: Text(t.done)),
          ]),
        ),
      );
    }
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(AppConfig.mockPayments ? t.mockPaymentTitle : t.paymentPending, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: AppSpacing.sm),
          Text(t.mockPaymentBody, style: const TextStyle(color: AppColors.textMuted)),
          const SizedBox(height: AppSpacing.md),
          KeyValueRow(t.total, formatInr(widget.checkout['amount'])),
          const SizedBox(height: AppSpacing.md),
          BusyButton(label: t.mockSucceed, icon: Icons.check, onPressed: () => _simulate('SUCCESS')),
          const SizedBox(height: AppSpacing.sm),
          BusyButton(label: t.mockFail, icon: Icons.close, outlined: true, onPressed: () => _simulate('FAILED')),
          TextButton(onPressed: () => _simulate('PENDING'), child: Text(t.mockPending)),
        ]),
      ),
    );
  }
}

/// SUB-028..030: billing history with invoice PDF download.
class BillingHistoryPage extends ConsumerWidget {
  const BillingHistoryPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final invoices = ref.watch(_invoicesProvider(ngoId ?? ''));
    return Scaffold(
      appBar: AppBar(title: Text(t.billingHistory)),
      body: invoices.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(_invoicesProvider)),
        data: (list) => list.isEmpty
            ? EmptyState(icon: Icons.receipt_long, title: t.noInvoices)
            : ListView.separated(
                padding: const EdgeInsets.all(AppSpacing.md),
                itemCount: list.length,
                separatorBuilder: (_, __) => const SizedBox(height: AppSpacing.sm),
                itemBuilder: (_, i) {
                  final inv = list[i];
                  return AppCard(
                    child: Row(children: [
                      const Icon(Icons.description_outlined, color: AppColors.primary),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${inv['number']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text(formatDate(inv['issuedAt']), style: const TextStyle(color: AppColors.textMuted)),
                      ])),
                      Text(formatInr(inv['total']), style: const TextStyle(fontWeight: FontWeight.w700)),
                      IconButton(
                        tooltip: t.download,
                        icon: const Icon(Icons.download),
                        onPressed: () async {
                          try {
                            final bytes = await ref.read(apiProvider).bytes('/api/v1/ngos/$ngoId/subscription/invoices/${inv['id']}/pdf');
                            final dir = await getApplicationDocumentsDirectory();
                            final f = File('${dir.path}/${'${inv['number']}'.replaceAll('/', '-')}.pdf');
                            await f.writeAsBytes(bytes, flush: true);
                            await OpenFilex.open(f.path);
                          } catch (e) {
                            if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                          }
                        },
                      ),
                    ]),
                  );
                },
              ),
      ),
    );
  }
}

final _invoicesProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, ngoId) async {
  ref.watch(sessionKeyProvider);
  if (ngoId.isEmpty) return [];
  final list = await ref.read(apiProvider).getList('/api/v1/ngos/$ngoId/subscription/invoices');
  return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});
