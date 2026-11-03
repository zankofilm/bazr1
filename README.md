# BAZR Inspector Android

اپلیکیشن Native اندروید برای بخش بازرسی سامانه BAZR.

## امکانات نسخه اولیه

- Native Android با Kotlin و Jetpack Compose (بدون WebView)
- رابط کاملاً فارسی و RTL با طراحی روشن سبز/نارنجی
- ورود اولیه با نام کاربری و رمز عبور بازرس
- فعال‌سازی اختیاری ورود با اثر انگشت/بیومتریک بعد از ورود موفق
- عدم ذخیره رمز عبور روی گوشی
- نگهداری Credential دستگاه با Android Keystore
- امضای درخواست ایجاد Session با RSA Key داخل Android Keystore
- دریافت مأموریت‌های اختصاص‌یافته از `mobile_sync.php`
- نمایش وضعیت زمانی مأموریت و جلوگیری سرور از اجرای خارج از بازه
- چک‌لیست، امتیازدهی ۰/۵۰/۱۰۰، توضیح برای امتیاز پایین
- ثبت عکس/PDF به‌عنوان مستند سؤال از طریق `mobile_upload.php`
- ارسال نهایی گزارش به `mobile_submit.php`
- نمایش رسیدها و وضعیت بررسی گزارش‌ها
- کش آخرین Sync و ذخیره پیش‌نویس پاسخ‌ها روی دستگاه
- GitHub Actions برای ساخت APK Debug

## 1) Patch سرور

فایل زیر را در پوشه `api/` نسخه BAZR 7.1.1 قرار دهید:

`server-patch/mobile_password_login.php`

این Endpoint ورود مستقیم بازرس را با همان username/password موجود فراهم می‌کند و سپس دستگاه را به API موبایل فعلی متصل می‌کند.

## 2) آدرس سرور

در صفحه ورود، دامنه اصلی سامانه را وارد کنید، مثلاً:

`https://inspection.example.ir`

اپ به‌صورت خودکار `/api/` را اضافه می‌کند. برای امنیت، HTTP معمولی پذیرفته نمی‌شود.

## 3) ساخت در Android Studio

1. Repository را Clone کنید.
2. پروژه را با Android Studio باز کنید.
3. JDK 17 و Android SDK 36 را داشته باشید.
4. در Android Studio از Gradle 8.13 استفاده کنید (یا Wrapper را از داخل Android Studio ایجاد کنید).
5. Sync Gradle را اجرا و ماژول `app` را Run کنید.

## 4) ساخت APK در GitHub

Repository را Push کنید. Workflow زیر خودکار اجرا می‌شود:

`.github/workflows/android.yml`

بعد از پایان Workflow از بخش **Actions > Artifacts** فایل `BAZR-Inspector-debug` را دریافت کنید.

## 5) ورود بیومتریک

- بار اول بازرس با username/password وارد می‌شود.
- بعد از ورود، کارت «ورود با اثر انگشت» روی صفحه خانه نمایش داده می‌شود.
- با فعال‌سازی، Device Credential با کلید AES دارای شرط `BIOMETRIC_STRONG` رمزگذاری می‌شود.
- رمز عبور کاربر ذخیره نمی‌شود.
- در اجرای بعدی، BiometricPrompt Credential را باز می‌کند و اپ با امضای کلید RSA دستگاه یک Session کوتاه‌مدت می‌گیرد.
- اگر اثر انگشت دستگاه تغییر کند یا کلید معتبر نباشد، کاربر باید دوباره با رمز وارد شود.

## نکات Production

- APK Release را با keystore سازمانی Sign کنید؛ keystore را داخل Git نگه ندارید.
- Branch Protection و Secret Scanning را روی Repository فعال کنید.
- برای انتشار عمومی، Play Integrity / MDM و Certificate Pinning را می‌توان در مرحله بعد اضافه کرد.
- این پروژه عمداً `android:allowBackup=false` دارد و Cleartext HTTP را غیرفعال کرده است.

## Toolchain

- AGP 8.13.2
- Gradle 8.13
- Kotlin 2.2.20
- Compose BOM 2026.09.00
- minSdk 26 / targetSdk 36
