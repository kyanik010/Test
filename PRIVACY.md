# Ideal Player Gizlilik Politikası / Ideal Player Privacy Policy

- Uygulama / App: **Ideal Player**
- Paket adı / Package name: `com.idealplayer.app`
- Uygulama işletmecisi / App operator: **MezTech**
- Yürürlük tarihi / Effective date: **24 Temmuz 2026 / July 24, 2026**
- Son güncelleme / Last updated: **24 Temmuz 2026 / July 24, 2026**
- Herkese açık sürüm / Public version: <https://idealplayer.netlify.app/privacy>
- Gizlilik iletişimi / Privacy contact: <idealplayer.support@gmail.com>

## Türkçe

### 1. Kapsam ve uygulamanın amacı

Bu politika, MezTech tarafından sunulan **Ideal Player** Android uygulaması için geçerlidir.
Ideal Player bir medya oynatıcısıdır; herhangi bir kanal, abonelik, oynatma listesi, yayın hizmeti
veya başka medya içeriği sağlamaz. Uygulamada kullanılan kaynakları kullanıcı ekler ve bu kaynaklara
erişim hakkından kullanıcı sorumludur.

### 2. Cihazınızda tutulan bilgiler

Uygulama şunları Android uygulama özel depolama alanında saklayabilir:

- oynatma listesi URL’leri ve seçilen yerel dosyaların Android belge URI’leri;
- Xtream sunucu adresleri, kullanıcı adları ve parolalar;
- EPG/XMLTV adresleri;
- favoriler, izleme geçmişi, devam et kayıtları ve oynatma konumları;
- uygulama, oynatıcı, dil ve EPG tercihleri ile önbelleğe alınmış metadata;
- ebeveyn denetimi ayarları ve izin verilen kategori listesi.

Ebeveyn PIN’i açık metin olarak saklanmaz. Rastgele salt ile PBKDF2 özeti oluşturulur ve bu özet
Android Keystore destekli şifreli uygulama tercihlerinde tutulur. Bu, uygulamanın tüm yerel
veritabanının ayrıca şifrelendiği anlamına gelmez.

Ideal Player, Android cloud backup ve cihazlar arası aktarımı kapatır. Android uygulama korumaları
normal kullanımda diğer uygulamaların erişimini sınırlar; root edilmiş veya güvenliği bozulmuş bir
cihaz bu korumayı zayıflatabilir.

### 3. Ağ bağlantıları ve üçüncü taraflar

İlgili özellik kullanıldığında uygulama şunlara ağ isteği gönderebilir:

- kullanıcının eklediği oynatma listesi, Xtream, yayın, görsel ve XMLTV/EPG sunucuları;
- dağıtımda TMDB API anahtarı etkinse metadata için TMDB API’si ve görsel sunucuları;
- isteğe bağlı Connected Ideal Player eşleştirme hizmeti.

Bu sunucular internet bağlantısının doğal sonucu olarak IP adresi gibi standart teknik bağlantı
bilgilerini görebilir ve kendi gizlilik koşullarına göre işleyebilir. Kullanıcı tarafından eklenen
bir kaynak `http://` kullanıyorsa o kaynakla trafik aktarım sırasında şifrelenmeyebilir. Mümkün
olduğunda güvenilir `https://` adresleri kullanılmalıdır.

TMDB metadata özelliği etkinse içerik başlığı, yıl, dil, içerik türü veya mevcut TMDB kimliği gibi
arama bilgileri TMDB’ye gönderilebilir. Dönen başlık, açıklama ve görsel bağlantıları uygulamada
önbelleğe alınabilir. Ideal Player, TMDB tarafından işletilmez veya desteklenmez.

### 4. Connected Ideal Player

Connected Ideal Player, kullanıcının telefondaki web formundan TV’ye kaynak bilgisi göndermesini
sağlayan isteğe bağlı bir özelliktir. Oynatma listesi URL’si; Xtream sunucu adresi, kullanıcı adı
ve parola; EPG adresi ve ilgili tercihler kısa süreli olarak Supabase altyapısında işlenebilir.
Bu yöntemle yerel oynatma listesi dosyası gönderilirse seçilen dosyanın içeriği de geçici
eşleştirme yükünün parçası olur.

Eşleştirme oturumu en fazla 10 dakika geçerlidir. Süresi dolan kayıtlar arka uç temizliği sırasında
silinir; TV tarafından alınan kayıt alındıktan hemen sonra silinir. Eşleştirme yükü reklam, analiz
veya profil oluşturma amacıyla kullanılmaz. Özellik yalnızca güvenli HTTPS web ve API adresleri
yapılandırılmışsa etkinleşir.

### 5. Reklam, analiz, satış ve güncellemeler

Uygulamada reklam SDK’sı veya analytics SDK’sı bulunmaz. Ideal Player kişisel verileri satmaz.
Google Play üzerinden dağıtılan `release` sürümünde uygulama içinden APK indiren veya kuran bir
güncelleyici bulunmaz; güncellemeler Google Play üzerinden sağlanır.

### 6. Saklama, silme ve seçimler

Yerel bilgiler, kullanıcı ilgili oynatma listesini, favoriyi veya izleme geçmişini silene ya da
uygulamayı kaldırana kadar cihazda kalabilir. Ayarlar ekranından izleme geçmişi temizlenebilir;
oynatma listeleri ve favoriler uygulama içinden silinebilir. Uygulamanın kaldırılması Android’in
uygulamaya özel yerel verilerini siler. Yedekleme ve cihaz aktarımı kapalı olduğundan Ideal Player
bu verileri yeni cihaza otomatik taşımaz.

Connected Ideal Player kayıtları yukarıdaki kısa süreli kurallara tabidir. Kullanıcının seçtiği
üçüncü taraf sunucuların kendi saklama ve silme kuralları için ilgili hizmete başvurulmalıdır.

### 7. Değişiklikler ve iletişim

Uygulamanın işlevleri veya hukuki gereksinimler değişirse politika güncellenebilir. Güncel sürüm
herkese açık sayfada yayımlanır ve son güncelleme tarihi değiştirilir. Gizlilikle ilgili sorular
için <idealplayer.support@gmail.com> adresine yazabilirsiniz.

## English

### 1. Scope and purpose of the app

This policy applies to the **Ideal Player** Android application provided by MezTech.
Ideal Player is a media player. It does not provide any channels, subscriptions, playlists,
streaming services, or other media content. Users add their own sources and are responsible for
having the right to access them.

### 2. Information kept on your device

The app may store the following in Android app-private storage:

- playlist URLs and Android document URIs for local files selected by the user;
- Xtream server addresses, usernames, and passwords;
- EPG/XMLTV addresses;
- favorites, watch history, continue-watching entries, and playback positions;
- app, player, language, and EPG preferences and cached metadata;
- parental-control settings and the list of allowed categories.

The parental PIN is not stored as plain text. It is converted to a PBKDF2 hash with a random salt,
and that hash is kept in encrypted app preferences backed by Android Keystore. This does not mean
that the app’s entire local database is separately encrypted.

Ideal Player disables Android cloud backup and device-to-device transfer. Android’s app sandbox
limits access by other apps during normal operation, but a rooted or otherwise compromised device
can weaken that protection.

### 3. Network connections and third parties

When the relevant feature is used, the app may make network requests to:

- playlist, Xtream, stream, image, and XMLTV/EPG servers added by the user;
- the TMDB API and image servers for metadata if the distribution has a TMDB API key enabled;
- the optional Connected Ideal Player pairing service.

These servers can receive standard technical connection information, such as an IP address, as a
necessary part of an internet request and may process it under their own terms. If a source added
by the user uses `http://`, traffic to that source may not be encrypted in transit. A trusted
`https://` address should be used whenever possible.

If TMDB metadata is enabled, search details such as a content title, year, language, content type,
or an existing TMDB identifier may be sent to TMDB. Titles, descriptions, and image links returned
by TMDB may be cached in the app. Ideal Player is not operated or endorsed by TMDB.

### 4. Connected Ideal Player

Connected Ideal Player is an optional feature for sending source details from a phone web form to
a TV. A playlist URL; Xtream server address, username, and password; EPG address; and related
preferences may be processed briefly by Supabase infrastructure. If a local playlist file is sent
this way, the selected file content also becomes part of the temporary pairing payload.

A pairing session is valid for no more than 10 minutes. Expired records are removed during backend
cleanup, and a record retrieved by the TV is deleted immediately after retrieval. Pairing payloads
are not used for advertising, analytics, or profiling. The feature is enabled only when secure
HTTPS web and API addresses are configured.

### 5. Advertising, analytics, sale, and updates

The app does not include an advertising SDK or analytics SDK. Ideal Player does not sell personal
data. The Google Play `release` version does not include an in-app updater that downloads or
installs APK files; updates are provided through Google Play.

### 6. Retention, deletion, and choices

Local information may remain on the device until the user deletes the relevant playlist, favorite,
or watch history, or uninstalls the app. Watch history can be cleared in Settings; playlists and
favorites can be deleted in the app. Uninstalling the app removes its app-private local data through
Android. Because backup and device transfer are disabled, Ideal Player does not automatically move
this data to a new device.

Connected Ideal Player records follow the short retention rules above. For retention or deletion by
a third-party server selected by the user, the user must contact that service under its own policy.

### 7. Changes and contact

This policy may be updated when the app’s functionality or legal requirements change. The current
version will be published on the public page and the last-updated date will be revised. For privacy
questions, email <idealplayer.support@gmail.com>.
