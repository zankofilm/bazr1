# Server patch for username/password Android login

Copy `mobile_password_login.php` into the existing BAZR `api/` directory next to `mobile_session.php` and `mobile_common.php`.

The endpoint:
- accepts only active `inspector` accounts from the existing `users` table;
- applies the same brute-force rate limiting used by the main login flow;
- checks the inspector profile and `mobileAppEnabled` flag;
- provisions/updates the matching `mobile_users` record;
- binds the Android installation public key to `mobile_devices`;
- refuses a previously revoked device;
- limits approved devices per inspector (default: 3);
- returns a device credential and a short-lived mobile session.

Optional config in `api/config.php`:

```php
'mobile_max_devices_per_user' => 3,
```

Use HTTPS only. The Android app refuses non-HTTPS server URLs.
