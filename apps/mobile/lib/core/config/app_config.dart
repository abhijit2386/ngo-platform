/// Build-time configuration (override with --dart-define). Defaults target local development: the emulator's
/// localhost:8080/8180 are forwarded to the development machine with `adb reverse` (see scripts/run-mobile.ps1),
/// so the token issuer is the same http://localhost:8180 the backend validates.
abstract final class AppConfig {
  static const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080');
  static const issuer = String.fromEnvironment('OIDC_ISSUER', defaultValue: 'http://localhost:8180/realms/ngo');
  static const clientId = String.fromEnvironment('OIDC_CLIENT_ID', defaultValue: 'ngo-mobile');
  static const redirectUri = 'org.ngoplatform.app:/oauthredirect';
  static const postLogoutRedirectUri = 'org.ngoplatform.app:/logout';
  static const scopes = ['openid', 'profile', 'email', 'offline_access'];

  /// Development builds talk to plain-HTTP local services; release builds must use HTTPS endpoints.
  static const allowInsecure = bool.fromEnvironment('ALLOW_INSECURE', defaultValue: true);

  /// Shows the mock payment sheet (development only; production uses a real provider checkout).
  static const mockPayments = bool.fromEnvironment('MOCK_PAYMENTS', defaultValue: true);

  static const appBuild = int.fromEnvironment('APP_BUILD', defaultValue: 1);
}
