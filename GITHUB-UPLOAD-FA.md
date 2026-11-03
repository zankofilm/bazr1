# راهنمای سریع قرار دادن روی GitHub

1. یک Repository خصوصی بسازید.
2. محتوای این پوشه را در ریشه Repository قرار دهید.
3. فایل `server-patch/mobile_password_login.php` را روی سرور داخل پوشه `api/` کپی کنید.
4. در GitHub به تب **Actions** بروید و Workflow `Android CI` را اجرا کنید.
5. پس از موفقیت Build، در پایین صفحه Run بخش **Artifacts** فایل `BAZR-Inspector-debug` را دانلود کنید.
6. برای نسخه نهایی قابل انتشار، Signing Release را با keystore سازمانی اضافه کنید؛ keystore را هرگز داخل Repository قرار ندهید.

آدرس سرور داخل خود اپ در صفحه ورود وارد می‌شود و نیازی به قراردادن دامنه در سورس نیست.
