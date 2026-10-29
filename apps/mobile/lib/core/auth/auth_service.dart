import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../config/app_config.dart';

enum AuthStatus { unknown, signedOut, signedIn, sessionExpired }

class AuthState {
  const AuthState(this.status, {this.claims = const {}});
  final AuthStatus status;
  final Map<String, dynamic> claims;
  bool get signedIn => status == AuthStatus.signedIn;
}

final authServiceProvider = Provider<AuthService>((ref) {
  final s = AuthService();
  ref.onDispose(s.dispose);
  return s;
});

final authStateProvider = StreamProvider<AuthState>((ref) {
  final s = ref.watch(authServiceProvider);
  return s.changes;
});

/// OIDC Authorization Code + PKCE against Keycloak (spec §3.5). Tokens live only in secure storage.
class AuthService {
  AuthService({FlutterAppAuth? appAuth, FlutterSecureStorage? storage})
      : _appAuth = appAuth ?? const FlutterAppAuth(),
        _storage = storage ?? const FlutterSecureStorage();

  final FlutterAppAuth _appAuth;
  final FlutterSecureStorage _storage;
  final _controller = StreamController<AuthState>.broadcast();
  AuthState _state = const AuthState(AuthStatus.unknown);
  Future<String?>? _refreshing;

  static const _kAccess = 'auth.access';
  static const _kRefresh = 'auth.refresh';
  static const _kIdToken = 'auth.id';
  static const _kExpiry = 'auth.expiry';

  Stream<AuthState> get changes async* {
    if (_state.status == AuthStatus.unknown) await restore();
    yield _state;
    yield* _controller.stream;
  }

  AuthState get current => _state;

  void _emit(AuthState s) {
    _state = s;
    _controller.add(s);
  }

  Future<void> restore() async {
    final refresh = await _storage.read(key: _kRefresh);
    final access = await _storage.read(key: _kAccess);
    if (refresh == null || access == null) {
      _state = const AuthState(AuthStatus.signedOut);
      return;
    }
    _state = AuthState(AuthStatus.signedIn, claims: decodeJwt(access));
  }

  /// Interactive sign in. [prompt]='login' forces re-authentication (step-up for sensitive changes).
  Future<void> signIn({String? prompt, String? loginHint, bool register = false}) async {
    final result = await _appAuth.authorizeAndExchangeCode(AuthorizationTokenRequest(
      AppConfig.clientId,
      AppConfig.redirectUri,
      issuer: AppConfig.issuer,
      scopes: AppConfig.scopes,
      // Keycloak opens its registration page for prompt=create.
      promptValues: register ? ['create'] : (prompt == null ? null : [prompt]),
      loginHint: loginHint,
      allowInsecureConnections: AppConfig.allowInsecure,
    ));
    await _store(result.accessToken, result.refreshToken, result.idToken, result.accessTokenExpirationDateTime);
    _emit(AuthState(AuthStatus.signedIn, claims: decodeJwt(result.accessToken!)));
  }

  Future<void> _store(String? access, String? refresh, String? id, DateTime? expiry) async {
    if (access == null) throw StateError('No access token');
    await _storage.write(key: _kAccess, value: access);
    if (refresh != null) await _storage.write(key: _kRefresh, value: refresh);
    if (id != null) await _storage.write(key: _kIdToken, value: id);
    await _storage.write(key: _kExpiry, value: (expiry ?? DateTime.now().add(const Duration(minutes: 5))).toIso8601String());
  }

  /// Returns a non-expired access token, refreshing when it expires within 30s.
  Future<String?> validAccessToken() async {
    final access = await _storage.read(key: _kAccess);
    if (access == null) return null;
    final exp = DateTime.tryParse(await _storage.read(key: _kExpiry) ?? '');
    if (exp != null && exp.isAfter(DateTime.now().add(const Duration(seconds: 30)))) return access;
    return refresh();
  }

  /// Single-flight token refresh.
  Future<String?> refresh() => _refreshing ??= _doRefresh().whenComplete(() => _refreshing = null);

  Future<String?> _doRefresh() async {
    final refresh = await _storage.read(key: _kRefresh);
    if (refresh == null) return null;
    try {
      final r = await _appAuth.token(TokenRequest(
        AppConfig.clientId,
        AppConfig.redirectUri,
        issuer: AppConfig.issuer,
        refreshToken: refresh,
        scopes: AppConfig.scopes,
        allowInsecureConnections: AppConfig.allowInsecure,
      ));
      await _store(r.accessToken, r.refreshToken ?? refresh, r.idToken, r.accessTokenExpirationDateTime);
      return r.accessToken;
    } catch (e) {
      debugPrint('Token refresh failed: ${e.runtimeType}');
      return null;
    }
  }

  void markSessionExpired() {
    if (_state.status == AuthStatus.signedIn) _emit(const AuthState(AuthStatus.sessionExpired));
  }

  Future<void> signOut() async {
    final idToken = await _storage.read(key: _kIdToken);
    try {
      if (idToken != null) {
        await _appAuth.endSession(EndSessionRequest(
          idTokenHint: idToken,
          issuer: AppConfig.issuer,
          postLogoutRedirectUrl: AppConfig.postLogoutRedirectUri,
          allowInsecureConnections: AppConfig.allowInsecure,
        ));
      }
    } catch (_) {
      // Local sign-out still proceeds if the browser round-trip is cancelled.
    }
    await _storage.deleteAll();
    _emit(const AuthState(AuthStatus.signedOut));
  }

  /// Clears this device's session without the browser round-trip (used when the account no longer exists,
  /// e.g. an under-18 sign-up that was refused and removed).
  Future<void> signOutLocally() async {
    await _storage.deleteAll();
    _emit(const AuthState(AuthStatus.signedOut));
  }

  void dispose() => _controller.close();

  static Map<String, dynamic> decodeJwt(String token) {
    try {
      final parts = token.split('.');
      final normalized = base64Url.normalize(parts[1]);
      return Map<String, dynamic>.from(jsonDecode(utf8.decode(base64Url.decode(normalized))) as Map);
    } catch (_) {
      return const {};
    }
  }
}
