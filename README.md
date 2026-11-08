# Watch Hub

تطبيق أندرويد يجمع نتفلكس وبرايم فيديو وكرنشي رول في مكان واحد.

## المزايا

- **الرئيسية:** الرائج هذا الأسبوع، وأفضل 10 على كل منصة، والجديد، والأعلى تقييماً، وترشيحات حسب قائمتك (للمسلسلات والأفلام).
- **صفحة العمل:** الإعلانات، والقصة بالتفصيل، والتقييم وعدد المقيّمين، وآراء المشاهدين، والممثلين، والمواسم، وأعمال مشابهة.
- **فتح مباشر:** زر «شاهد» يفتح صفحة العمل نفسه في تطبيق المنصة (الرابط من Wikidata)، وإذا ما لقينا رابط مباشر يفتح البحث فيها.
- **بحث موحد:** ابحث عن أي فيلم أو مسلسل أو أنمي واعرف على أي منصة متوفر في منطقتك.
- **قائمة مشاهدة موحدة:** «أبي أشوفه» و«أتابعه» و«خلصته» من كل المنصات في قائمة وحدة.
- **تتبع الحلقات:** سجّل وين وصلت في كل مسلسل، والتطبيق ينبهك إذا نزلت حلقات ما شفتها.
- **متابعة الاشتراكات:** كم تدفع بالشهر والسنة، ومتى يتجدد كل اشتراك.

التطبيق **ما يشغّل الفيديو بنفسه ولا يحفظ كلمات المرور**. التشغيل دايماً يصير في التطبيق الرسمي لكل منصة، وكل بياناتك محفوظة على جوالك فقط.

## التثبيت

1. افتح صفحة [Releases](../../releases) من الجوال وحمّل `WatchHub.apk` من آخر إصدار.
2. ثبّته، وإذا طلب منك جوالك اسمح بالتثبيت من مصادر خارجية.
3. سوّ حساب مجاني في [TMDB](https://www.themoviedb.org/signup)، وانسخ المفتاح من [صفحة الـ API](https://www.themoviedb.org/settings/api)، وحطه في الإعدادات داخل التطبيق.

## التطوير

- Kotlin + Jetpack Compose (Material 3)، أقل إصدار أندرويد 8.0 (API 26).
- بيانات الأفلام والتوفر من [TMDB API](https://developer.themoviedb.org/) (والتوفر مصدره JustWatch).
- GitHub Actions يبني الـ APK مع كل push (`.github/workflows/android.yml`).

```bash
./gradlew assembleDebug
```

This product uses the TMDB API but is not endorsed or certified by TMDB.

## نسخة الويب (للآيفون والآيباد والكمبيوتر)

مجلد `web/` فيه نسخة ويب من التطبيق: الرئيسية والبحث وصفحة العمل وقائمتي، وصفحة «مباشر» لقنوات Xtream Codes، والأفلام والمسلسلات من حسابك، وزر «مشاهدة» اللي يشغّل العمل من حسابك.

المتصفح ما يقدر يوصل لسيرفرات Xtream مباشرة (أغلبها `http` وما تسمح للمتصفحات)، فالموقع يجي مع سيرفر صغير (`web/server.ts`) يعرض الصفحات ويمرّر طلبات Xtream والفيديو عن طريق `/proxy`.

### التشغيل على Render (مجاني)

1. سجّل في [Render](https://render.com) بحساب GitHub.
2. من لوحة التحكم: **New** ثم **Blueprint**، واختر مستودع `watch-hub` والفرع اللي فيه مجلد `web`.
3. Render يقرأ ملف `render.yaml` ويسوي كل شي بنفسه. بيطلب منك قيمة `PROXY_KEY`: اكتب كلمة سر من اختيارك.
4. بعد ما يخلص البناء، افتح الرابط (`https://watch-hub-xxxx.onrender.com`)، وحط من الإعدادات مفتاح TMDB وحساب Xtream، وكلمة السر نفسها في «مفتاح البروكسي».

الخطة المجانية تنام بعد ربع ساعة بدون استخدام، وأول فتحة بعدها تاخذ تقريباً دقيقة.

على الآيفون: افتح الرابط في Safari، واضغط «مشاركة» ثم «إضافة إلى الشاشة الرئيسية» عشان يصير مثل التطبيق.

### أي استضافة ثانية

`web/Dockerfile` يشغّل الموقع على أي استضافة تدعم Docker (Render أو Railway أو سيرفر خاص). وعلى جهازك:

```bash
deno task web   # http://localhost:8000
```

بيانات الموقع (القائمة والمفضلة والإعدادات) محفوظة في المتصفح نفسه، ومنفصلة عن تطبيق أندرويد.

## حساب Watch Hub (تسجيل دخول بـ Google ومزامنة بين الأجهزة)

لما تسجّل دخول بنفس حساب Google على الجوال والتلفاز والموقع، تصير «قائمتي» و«تابع المشاهدة» (لين الثانية اللي وقفت عندها) وحسابات Xtream نفسها على كل الأجهزة، وتتحدّث خلال ثانية. توقف حلقة في الجوال، وتفتحها في التلفاز تكمل من نفس المكان.

البيانات في Firebase (مجاني)، وكل شخص ما يقدر يقرأ إلا بياناته (القواعد في `firestore.rules`). كلمات مرور حسابات Xtream تنحفظ في حسابك عشان ما تكتبها مرة ثانية في التلفاز.

### الإعداد (مرة وحدة)

**١. مشروع Firebase**
1. افتح [console.firebase.google.com](https://console.firebase.google.com) ← **Create a project** (مثلاً `watch-hub`). تقدر تطفي Google Analytics.

**٢. تسجيل الدخول بـ Google**
1. **Build ← Authentication ← Get started ← Sign-in method ← Google ← Enable**، اختر إيميلك ← **Save**.
2. افتح Google مرة ثانية، وتحت **Web SDK configuration** انسخ **Web client ID** ← هذا `GOOGLE_WEB_CLIENT_ID`.
3. **Authentication ← Settings ← Authorized domains ← Add domain**: `watch-hub-ddfg.onrender.com` (رابط موقعك بدون https).

**٣. قاعدة البيانات**
1. **Build ← Firestore Database ← Create database** ← اختر أقرب موقع ← **Start in production mode**.
2. تبويب **Rules** ← امسح اللي فيه والصق محتوى ملف `firestore.rules` ← **Publish**.

**٤. التطبيقات**
1. ⚙️ **Project settings ← General ← Your apps ← `</>` (Web)** ← اكتب اسم ← **Register app**. من الإعدادات اللي تطلع انسخ:
   - `apiKey` ← `FIREBASE_API_KEY`
   - `projectId` ← `FIREBASE_PROJECT_ID`
   - `appId` ← `FIREBASE_APP_ID`
2. **Add app ← Android**:
   - Package name: `com.alyoushw.watchhub`
   - SHA-1: `C4:DF:D2:E7:F8:9F:A5:A1:C4:86:21:5D:F3:85:C9:22:5E:92:2E:19`
   - **Register app** (ما تحتاج تنزّل `google-services.json`) ← انسخ **App ID** (يبدأ بـ `1:` وفيه `android`) ← `FIREBASE_ANDROID_APP_ID`.

**٥. الدخول برمز (للتلفاز)**
1. افتح [console.cloud.google.com/apis/credentials](https://console.cloud.google.com/apis/credentials) واختر نفس المشروع.
2. **Create credentials ← OAuth client ID ← Application type: TVs and Limited Input devices** ← **Create**.
3. انسخ **Client ID** ← `GOOGLE_TV_CLIENT_ID`، و**Client secret** ← `GOOGLE_TV_CLIENT_SECRET`.
4. ارجع لـ Firebase ← Authentication ← Sign-in method ← Google ← **Safelist client IDs from external projects** ← أضف نفس الـ Client ID ← Save.
5. في **OAuth consent screen**: إذا مكتوب **Testing** اضغط **Publish app** (أو أضف إيميلك في Test users)، عشان يقبل الدخول.

**٦. Render**
في خدمة الموقع ← **Environment** ← أضف المتغيرات السبعة:
`FIREBASE_API_KEY`، `FIREBASE_PROJECT_ID`، `FIREBASE_APP_ID`، `FIREBASE_ANDROID_APP_ID`، `GOOGLE_WEB_CLIENT_ID`، `GOOGLE_TV_CLIENT_ID`، `GOOGLE_TV_CLIENT_SECRET` ← **Save** (يعيد النشر).

### تسجيل الدخول
- **الموقع:** الإعدادات ← حسابك ← «سجّل دخول بـ Google» (أو «دخول برمز» إذا ما انفتحت النافذة، مثلاً من أيقونة الشاشة الرئيسية في الآيفون).
- **الجوال:** الإعدادات ← حسابك ← «سجّل دخول بـ Google».
- **التلفاز:** الإعدادات ← حسابك ← «سجّل دخول برمز» ← افتح `google.com/device` من جوالك واكتب الرمز اللي على الشاشة.

التطبيق ياخذ هالإعدادات من موقعك (`/config.json`)، فما يحتاج APK جديد إذا غيّرت شي فيها. إذا موقعك برابط ثاني، غيّره من «رابط موقعك» تحت قسم حسابك.
