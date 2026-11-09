# A complete app (architecture v2)

Moved out of the README; nothing was removed.

A small chat app shows the whole architecture: globals, routes, middleware,
composables and screens that use only `setup()`. The full reference is
[Architecture v2](docs/architecture-v2.md).

```
index.php
routes.php
src/
  Api/Api.php
  Composables/useAuth.php
  Middleware/Authenticate.php
  Screens/LoginScreen.pam.php
  Screens/ChatsScreen.pam.php
  Screens/ChatScreen.pam.php
```

**`index.php`** boots the app. The globals are visible in every template and
component.

```php
<?php

use App\Middleware\Authenticate;
use Pam\Native\App;
use function App\Composables\useAuth;
use function Pam\Native\{useColorScheme, useSafeAreaInsets, useNetwork};

require __DIR__.'/vendor/autoload.php';

App::globals([
    'scheme' => useColorScheme(),
    'insets' => useSafeAreaInsets(),
    'online' => useNetwork(),
    'auth'   => useAuth(),
]);
App::middleware(['auth' => Authenticate::class]);
App::run(require __DIR__.'/routes.php');
```

**`routes.php`** declares typed, Laravel-style routes. The `{id}` parameter
arrives in `ChatScreen::setup(string $id)`.

```php
<?php

use App\Screens\{ChatScreen, ChatsScreen, LoginScreen};
use Pam\Native\Route;

Route::screen('/login', LoginScreen::class)->name('login');

Route::middleware('auth')->group(function () {
    Route::screen('/chats', ChatsScreen::class)->name('chats');
    Route::screen('/chat/{id}', ChatScreen::class)->name('chat');
});
```

**`src/Api/Api.php`** is the app's HTTP client, with the JWT and 401
interceptors.

```php
<?php

namespace App\Api;

use Pam\Native\Http\{Http, HttpClient, HttpError, Request};
use function App\Composables\useAuth;

final class Api
{
    private static ?HttpClient $client = null;

    public static function client(): HttpClient
    {
        return self::$client ??= Http::create(baseUrl: 'https://api.example.com')
            ->onRequest(fn (Request $request) => ($token = useAuth()->token->value) !== null
                ? $request->withToken($token) : $request)
            ->onError(function (HttpError $error) {
                if ($error->status === 401) {
                    useAuth()->logout();
                }
                throw $error;
            });
    }
}
```

**`src/Composables/useAuth.php`** is an app composable: shared state, a token
kept in SecureStorage, and navigation on login and logout.

```php
<?php

namespace App\Composables;

use App\Api\Api;
use Pam\Native\Signals\Signal;
use Pam\Native\Storage\SecureStorage;
use function Pam\Native\{navigate, signal};

final class Auth
{
    /** @param Signal<?string> $token @param Signal<?array> $user */
    public function __construct(public readonly Signal $token, public readonly Signal $user)
    {
        SecureStorage::get('token', fn (?string $saved) => $this->token->value = $saved);
    }

    public function login(string $email, string $password): void
    {
        Api::client()->post('/login', ['email' => $email, 'password' => $password])
            ->then(function ($response) {
                $this->token->value = $response->json()['token'];
                $this->user->value = $response->json()['user'];
                SecureStorage::set('token', $this->token->value);
                navigate('chats');
            });
    }

    public function logout(): void
    {
        $this->token->value = null;
        $this->user->value = null;
        SecureStorage::delete('token');
        navigate('login');
    }
}

function useAuth(): Auth
{
    static $auth = null;

    return $auth ??= new Auth(signal(null), signal(null));
}
```

**`src/Middleware/Authenticate.php`** sends signed-out users to the login
screen.

```php
<?php

namespace App\Middleware;

use Closure;
use Pam\Native\{Middleware, Route};
use function App\Composables\useAuth;
use function Pam\Native\redirect;

final class Authenticate implements Middleware
{
    public function handle(Route $to, Closure $next): mixed
    {
        return useAuth()->token->value !== null ? $next($to) : redirect('login');
    }
}
```

**`src/Screens/ChatsScreen.pam.php`** is a paginated, cached list that
refreshes when the screen is focused again. Theme, safe area and connectivity
come from the globals.

```php
<?php

namespace App\Screens;

use App\Api\Api;
use Pam\Native\Component;
use Pam\Native\Composables\Resource;
use function Pam\Native\{navigate, useResource};

final class ChatsScreen extends Component
{
    public Resource $chats;

    public function setup(): void
    {
        $this->chats = useResource(
            fn (?string $cursor) => Api::client()->get('/chats', ['cursor' => $cursor])->json(),
            paginate: true, refreshOnFocus: true, cache: 'chats',
        );
    }

    public function open(int $id): void { navigate('chat', ['id' => $id]); }
    public function refresh(): void { $this->chats->reload(); }
    public function more(): void { $this->chats->more(); }
}
?>

<template>
    <Column class="screen" :class="['dark' => $scheme.dark]" :paddingTop="$insets.top">
        <Text p-if="!$online.value" class="offline">You are offline</Text>
        <VirtualizedList :refreshing="$chats.refreshing" on:refresh="refresh" on:endReached="more">
            <Pressable p-for="$chat in $chats.data" :key="$chat['id']" @press="open($chat['id'])">
                <Text class="title">{{ $chat['title'] }}</Text>
            </Pressable>
        </VirtualizedList>
        <View :height="$insets.bottom" />
    </Column>
</template>
```

**`src/Screens/ChatScreen.pam.php`** receives the route parameter, follows
the keyboard and sends messages optimistically.

```php
<?php

namespace App\Screens;

use App\Api\Api;
use Pam\Native\Component;
use Pam\Native\Composables\{KeyboardState, Resource};
use function Pam\Native\{onBlur, useKeyboard, useResource};

final class ChatScreen extends Component
{
    public string $id;
    public string $draft = '';
    public Resource $messages;
    public KeyboardState $keyboard;

    public function setup(string $id): void
    {
        $this->id = $id;
        $this->keyboard = useKeyboard();
        $this->messages = useResource(
            fn (?string $cursor) => Api::client()->get("/chats/{$id}/messages", ['cursor' => $cursor])->json(),
            paginate: true, cache: "chat:{$id}",
        );
        onBlur(fn () => $this->keyboard->dismiss());
    }

    public function send(): void
    {
        $tmp = ['id' => 'tmp-'.bin2hex(random_bytes(4)), 'text' => $this->draft];
        $this->draft = '';
        $this->messages->prepend($tmp);
        Api::client()->post("/chats/{$this->id}/messages", ['text' => $tmp['text']])
            ->then(fn ($response) => $this->messages->replace($tmp['id'], $response->json()))
            ->catch(fn () => $this->messages->markFailed($tmp['id']));
    }

    public function more(): void { $this->messages->more(); }
}
?>

<template>
    <Column class="screen" :paddingTop="$insets.top" :paddingBottom="$keyboard.visible ? $keyboard.height : $insets.bottom">
        <VirtualizedList inverted on:endReached="more">
            <Text p-for="$message in $messages.data" :key="$message['id']" :class="['failed' => $message['_failed'] ?? false]">
                {{ $message['text'] }}
            </Text>
        </VirtualizedList>
        <Row>
            <Input p-model="$draft" />
            <Button label="Send" @press="send" />
        </Row>
    </Column>
</template>
```

Code written before v2 keeps working: `mount()`, constructor route parameters,
`Appearance::onChange()` and the other earlier APIs are still supported. They
are marked `@deprecated` and each one points to its replacement.
