# Migrating to PAM Native 0.6

PAM Native 0.6 keeps the lower-level navigation API source-compatible. New
applications should use named routes; existing applications can migrate one
root navigator at a time.

## Stack declaration

Before:

```php
$navigator = Router::stack('home')
    ->route('home', fn () => $home)
    ->route('product', fn (RouteContext $route) => new ProductScreen(
        $route->integer('productId'),
    ))
    ->build();
```

After:

```php
$navigator = Route::stack('main', initial: 'home', routes: function (): void {
    Route::screen('home', HomeScreen::class);
    Route::screen('product', ProductScreen::class);
});
```

```php
final class ProductScreen extends Component
{
    public function __construct(public readonly int $productId)
    {
    }
}
```

## Navigation from components

Remove injected `Navigator` properties and use the application navigation
scope:

```php
$this->pushRoute('product', productId: 42);
$this->navigateRoute('account');
$this->replaceRoute('login');
$this->popRoute();
```

Low-level infrastructure may continue using `Navigator`, `Router`,
`NavigationContainer`, typed actions and events directly.

## Deep links

Internal route names do not contain URL paths. Add a path only when the route
must accept an external URL:

```php
Route::screen('product', ProductScreen::class)
    ->deepLink('/products/{productId}');
```

## Protocol

Protocol version remains `1` and all changes are append-only:

- node kind `Canvas = 31`;
- property `CanvasCommands = 440`.
- property `PressScale = 448`.

PHP, Rust, Android and iOS artifacts must be upgraded together when Canvas is
used. Existing protocol frames remain valid.
