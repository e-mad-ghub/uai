# Android Brand Icon Pack

This package includes:

- Adaptive launcher icon
- Monochrome themed icon for Android 13+
- In-app vector icon with configurable theme colors
- Motion version as an AnimatedVectorDrawable
- Day/Night color resources
- Sample usage snippets

## Important note

- **Launcher icons do not animate on the Android home screen.**
- The motion version is intended for:
  - splash screen
  - onboarding
  - empty states
  - toolbar/header branding
  - in-app status or identity moments

---
## Included files

### Launcher
- `res/mipmap-anydpi-v26/ic_launcher.xml`
- `res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `res/drawable/ic_launcher_foreground.xml`
- `res/drawable/ic_launcher_background.xml`

### Themed launcher icon
- `res/drawable/ic_launcher_monochrome.xml`

### In-app icons
- `res/drawable/ic_brand.xml`
- `res/drawable/ic_brand_monochrome.xml`

### Motion version
- `res/drawable/avd_ic_brand_pulse.xml`
- `res/drawable/ic_brand_animatable.xml`
- `res/animator/brand_face_pulse_scale_x.xml`
- `res/animator/brand_face_pulse_scale_y.xml`
- `res/animator/brand_glasses_pulse_alpha.xml`

### Theme colors
- `res/values/colors.xml`
- `res/values-night/colors.xml`

---
## 1) Copy files into your Android app

Copy the `res` folder contents into your app module:

- `app/src/main/res/...`

---
## 2) Configure launcher icon

In `AndroidManifest.xml`, make sure your application uses:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    ... />
```

For Android 13 themed icons, the launcher will use the monochrome layer automatically when supported.

---
## 3) Configure colors

The icon pack uses resource colors so you can easily change branding by theme.

### Day colors
Edit `res/values/colors.xml`

### Night colors
Edit `res/values-night/colors.xml`

Current palette:
- Background: deep navy
- Accent: cool blue
- Accent secondary: electric violet
- On-brand: near white
- Glasses fill: dark ink

---
## 4) Use the static in-app icon

In XML:

```xml
<ImageView
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:src="@drawable/ic_brand"
    android:contentDescription="@string/app_name" />
```

In Jetpack Compose:

```kotlin
Icon(
    painter = painterResource(R.drawable.ic_brand),
    contentDescription = stringResource(R.string.app_name),
    tint = Color.Unspecified
)
```

`Color.Unspecified` preserves the drawable's own resource colors.

---
## 5) Use the motion version

### XML View usage

```xml
<ImageView
    android:id="@+id/brandIcon"
    android:layout_width="72dp"
    android:layout_height="72dp"
    android:src="@drawable/avd_ic_brand_pulse"
    android:contentDescription="@string/app_name" />
```

In Activity or Fragment:

```kotlin
val imageView = findViewById<ImageView>(R.id.brandIcon)
val drawable = imageView.drawable
if (drawable is Animatable) {
    drawable.start()
}
```

### Compose usage

`AnimatedVectorDrawable` XML can still be used from Compose:

```kotlin
Image(
    painter = painterResource(R.drawable.avd_ic_brand_pulse),
    contentDescription = stringResource(R.string.app_name)
)
```

For precise lifecycle-controlled animation in Compose, you can also recreate the same motion using Compose animation APIs.

---
## 6) What is animated

The motion version adds a subtle premium effect:
- the circular face gently pulses
- the sunglasses softly shift opacity

This keeps the brand feeling alive without becoming distracting.

---
## 7) Customization suggestions

### More premium
- Increase background darkness
- Reduce pulse scale from `1.04` to `1.02`
- Slow duration from `1400ms` to `1800ms`

### More energetic
- Increase pulse scale to `1.06`
- Decrease duration to `1100ms`

### Fully monochrome
Use:
- `@drawable/ic_brand_monochrome`
- `@drawable/ic_launcher_monochrome`

---
## 8) Notes

- Adaptive launcher icon background uses a fixed brand color resource for reliability across launchers.
- In-app icons are vector based and easy to recolor.
- The monochrome icon is provided for Android themed launcher support.
- If you want, this pack can be extended later with:
  - notification icon
  - splash screen variant
  - widget icon
  - Compose-native animated version
