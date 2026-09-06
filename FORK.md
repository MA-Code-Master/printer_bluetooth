# thermal_printer — نسخة معدّلة (fork)

نسخة من `thermal_printer 1.0.5` من pub.dev (أحدث إصدار موجود، والباكدج شكله متوقّف
عن التطوير — الكود أصلاً مبني على عيّنة `BluetoothChat` القديمة بتاعة أندرويد).

بتتربط في `Maly-POS` كـ git dependency **بـ commit مثبّت** — عشان أي push هنا
ما يغيّرش اللي بيتبني في نقاط البيع من غير ما حد ياخد باله:

```yaml
thermal_printer:
  git:
    url: https://github.com/MA-Code-Master/printer_bluetooth.git
    ref: <commit sha>
```

يعني بعد أي تعديل هنا لازم تحدّث الـ `ref` في `pubspec.yaml` بتاع التطبيق،
وإلا التعديل مش هيوصل.

كل سطر اتغيّر عليه تعليق فيه كلمة **`MALY-POS FORK`**، فتقدر تلاقيهم كلهم بـ:

```bash
grep -rn "MALY-POS FORK" .
```

> **مهم:** التعديلات دي Kotlin (كود أصلي)، يعني **مش هتنزل كـ Shorebird patch**.
> محتاجة release كامل جديد.

---

## ١. الكتابة الفاشلة كانت بتترجّع كنجاح — سبب ضياع الفواتير

`BluetoothConnection.ConnectedThread.write()` · `BluetoothService.sendDataByte()`

الأصل:

```kotlin
fun write(bytes: ByteArray?) {
    try { mmOutStream?.write(bytes) }
    catch (e: IOException) { /* toast */ return }   // ← الخطأ بيتبلع
}

fun sendDataByte(bytes: ByteArray?): Boolean {
    if (state == STATE_CONNECTED) { write(bytes!!); return true }  // ← true دايماً
    return false
}
```

`write` كانت بترجع `Unit` وبتبلع الـ `IOException`، و`sendDataByte` كانت بتحدد قيمة
الإرجاع من **حالة الاتصال قبل الكتابة** مش من نتيجتها. يعني الـ plugin كان بيقول
"اتبعت" لبايتات عمرها ما وصلت الطابعة → التطبيق يسجّل طباعة ناجحة → حلقة إعادة
المحاولة ما بتشتغلش → **الفاتورة تضيع من غير أي رسالة**.

بعد التعديل: `write` بترجع `Boolean`، و`sendDataByte` بترجّعها زي ما هي.

أضفنا كمان **`flush()`** — الأصل عمره ما عمل flush، فآخر جزء من الفاتورة كان
بيفضل في بافر الـ stream لما الـ socket يتقفل بعدها على طول، وده كان بيقطع أوامر
التغذية والقص.

نفس التغيير اتعمل في `BluetoothBleConnection.write()` و`IBluetoothConnection`
عشان التوقيع يفضل متسق.

## ٢. إذن الموقع كان بيمنع البلوتوث خالص على أندرويد ١٢+

`ThermalPrinterPlugin.checkPermissions()`

الأصل كان بيطلب `ACCESS_FINE_LOCATION` على **كل** إصدارات الأندرويد قبل ما يعرض أو
يتصل بأي جهاز بلوتوث — وبيطلبها **من غير** `ACCESS_COARSE_LOCATION`. وأندرويد ١٢
غيّر القاعدة: طلب FINE من غير COARSE **النظام بيتجاهله تماماً** — لا dialog ولا منح.

فمن API 31 الدالة دي مستحيل تنجح، و`getBluetoothList` / `onStartConnection` كانوا
بيرجعوا **من غير ما يردوا على الـ method call أصلاً**. ومن ناحية Dart ده مش متميّز
عن "مفيش طابعات مقترنة": الـ stream يفضل فاضي لحد الـ timeout. الأجهزة القديمة ما
تأثرتش — وده بالظبط ليه الطابعات شغالة عليها ومش شغالة على غيرها.

الإذن ده مكنش محتاج أصلاً لما الـ plugin بيعمله على أندرويد الحديث:
`BLUETOOTH_SCAN` معرّف بـ `neverForLocation`، وسرد الأجهزة المقترنة بحث مش مسح
راديو. تحت API 31 المنصة بتطلبه فعلاً، فسايبينه هناك.

اتصلّح كمان انهيار: `requestPermissions` على `currentActivity!!` وهي `null` كانت
بترمي NPE جوه معالج الـ method call، والـ `invokeMethod` في Dart مش بيعمل await
فالخطأ كان بيضيع من غير أي أثر.

## ٣. التوستات

`BluetoothConnection.connectionLost()` · `connectionFailed()` ·
`ConnectedThread.write()` · `ThermalPrinterPlugin.onRequestPermissionsResult()`

`connectionLost()` كانت بتظهر **"Bluetooth connection lost"** بالإنجليزي في نص
شاشة البيع. وهي بتشتغل على **كل** إغلاق للـ socket مش على الأعطال بس: الـ reader
thread قاعد blocked على `read()`، فلما إحنا نقفل الاتصال بنفسنا (تبديل طابعة،
إعادة اتصال) الـ read بترمي وتوصل هنا برضه. يعني الكاشير كان بيشوف الرسالة دي بعد
طباعات **نجحت فعلاً**.

اتشالت كل التوستات وبقت `Log` بدلها. تغيير الحالة (`state`) لسه بيتبعت زي ما هو —
وده اللي بيوصل لـ Dart على الـ event channel، وبيستخدمه `PrinterHelper` عشان يكشف
اللينك اللي بيقع في نص الفاتورة.

## ٤. `disconnect()` كانت بترجع `false` دايماً

`lib/src/connectors/bluetooth.dart`

كانت بترجع `false` ثابتة حتى لما تنجح، فمكنش ينفع تفرّق بين socket اتقفل وواحد رفض
يتقفل — وsocket نص مفتوح بيخلي كل محاولة اتصال بعدها تعمل timeout.

---

## لو حبيت ترجع للأصل

في `pubspec.yaml` بتاع التطبيق، رجّع السطر لـ `thermal_printer:` من غير بلوك
`git:` واعمل `flutter pub get`. الباكدج هيرجع ياخد نسخة pub.dev، **وكل الأعطال
فوق هترجع معاها** — وأهمها ضياع الفواتير الصامت.
