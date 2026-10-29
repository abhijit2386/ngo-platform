import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ngo_app/core/api/api_error.dart';
import 'package:ngo_app/core/util/validators.dart';
import 'package:ngo_app/design_system/components.dart';
import 'package:ngo_app/localization/app_localizations_en.dart';

void main() {
  final v = V(L10nEn());

  group('phone validation (Indian mobile)', () {
    test('accepts 10 digits starting 6-9', () {
      for (final p in ['9876543210', '6000000000', '7123456789', '8999999999']) {
        expect(v.phoneField()(p), isNull, reason: p);
      }
    });
    test('rejects wrong length, prefix or characters', () {
      for (final p in ['5876543210', '987654321', '98765432100', '98765-4321', '+919876543210', 'abcdefghij']) {
        expect(v.phoneField()(p), isNotNull, reason: p);
      }
    });
    test('optional allows blank but still validates content', () {
      expect(v.phoneField(optional: true)(''), isNull);
      expect(v.phoneField(optional: true)('123'), isNotNull);
    });
  });

  group('PIN code', () {
    test('six digits, not starting with 0', () {
      expect(v.pinField('400001'), isNull);
      expect(v.pinField('012345'), isNotNull);
      expect(v.pinField('40001'), isNotNull);
      expect(v.pinField('4000011'), isNotNull);
      expect(v.pinField(''), isNotNull);
    });
  });

  group('PAN and Darpan', () {
    test('PAN format', () {
      expect(v.panField('ABCDE1234F'), isNull);
      expect(v.panField('abcde1234f'), isNull, reason: 'case-insensitive client side; server uppercases');
      expect(v.panField('ABCD1234F'), isNotNull);
      expect(v.panField('ABCDE12345'), isNotNull);
      expect(v.panField(''), isNull, reason: 'optional');
    });
    test('Darpan format', () {
      expect(v.darpanField('MH/2017/0123456'), isNull);
      expect(v.darpanField('MH/17/0123456'), isNotNull);
      expect(v.darpanField('MH-2017-0123456'), isNotNull);
    });
  });

  group('email', () {
    test('valid and invalid', () {
      expect(v.emailField()('a@b.org'), isNull);
      expect(v.emailField()('a.b+c@ngo.co.in'), isNull);
      expect(v.emailField()('a@b'), isNotNull);
      expect(v.emailField()('ab.org'), isNotNull);
      expect(v.emailField()(''), isNotNull);
    });
  });

  group('amount', () {
    final a = v.amount(min: 1, max: 1000000);
    test('accepts rupees with up to 2 decimals', () {
      expect(a('500'), isNull);
      expect(a('500.5'), isNull);
      expect(a('500.50'), isNull);
    });
    test('rejects zero, negative, 3 decimals, text and out of range', () {
      for (final s in ['0', '-5', '500.505', 'abc', '1e3', '1000001', '']) {
        expect(a(s), isNotNull, reason: s);
      }
    });
  });

  group('text rules', () {
    test('length and pattern', () {
      final f = v.text(min: 3, max: 10, pattern: V.name);
      expect(f('Asha'), isNull);
      expect(f('आशा सेवा'), isNull, reason: 'Devanagari allowed');
      expect(f('ab'), isNotNull);
      expect(f('abcdefghijk'), isNotNull);
      expect(f('<script>'), isNotNull);
    });
  });

  group('INR formatting', () {
    test('Indian grouping', () {
      expect(formatInr('1234567.5'), '₹12,34,567.50');
      expect(formatInr(597, whole: true), '₹597');
    });
  });

  group('ApiError mapping', () {
    test('maps backend error body with field errors', () {
      final e = DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: 400, data: {
          'code': 'VALIDATION_FAILED',
          'message': 'One or more fields are invalid',
          'correlationId': 'abc-123',
          'fieldErrors': [
            {'field': 'pinCode', 'code': 'Pattern', 'message': 'PIN code must be 6 digits'}
          ],
        }),
      );
      final err = ApiError.from(e);
      expect(err.code, 'VALIDATION_FAILED');
      expect(err.status, 400);
      expect(err.fieldErrors['pinCode'], 'PIN code must be 6 digits');
      expect(err.correlationId, 'abc-123');
    });
    test('network failure has friendly message', () {
      final err = ApiError.from(DioException(requestOptions: RequestOptions(path: '/x'), type: DioExceptionType.connectionError));
      expect(err.isNetwork, isTrue);
    });
    test('step-up and entitlement flags', () {
      Response r(int s, String code) => Response(requestOptions: RequestOptions(path: '/'), statusCode: s, data: {'code': code, 'message': 'm'});
      expect(ApiError.from(DioException(requestOptions: RequestOptions(path: '/'), response: r(401, 'STEP_UP_REQUIRED'))).needsStepUp, isTrue);
      expect(ApiError.from(DioException(requestOptions: RequestOptions(path: '/'), response: r(402, 'ENTITLEMENT_REQUIRED:X'))).isEntitlement, isTrue);
    });
  });
}
