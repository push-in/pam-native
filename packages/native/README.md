<!-- pam:distribution-page:start -->
<div align="center">

# PAM Native — Composer Distribution

**The public Composer source for the typed PAM Native PHP SDK.**

This repository is an immutable subtree split of the canonical PAM Native release. Applications install from here through Packagist; architecture, native hosts, issues, and contributions live in the canonical repository.

[![Packagist](https://img.shields.io/packagist/v/pushinbr/pam-native?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Status](https://img.shields.io/badge/repository-release%20mirror-64748b?style=flat-square)

**[Canonical source](https://github.com/push-in/pam-native) · [Documentation](https://push-in.github.io/pam-docs/native/overview/) · [Packagist](https://packagist.org/packages/pushinbr/pam-native) · [Issues](https://github.com/push-in/pam-native/issues)**

</div>

---

## Install the product

```bash
pam composer require pushinbr/pam-native
pam doctor --fix
```

Composer records this mirror's immutable tag and commit in your lockfile. Do not add this Git repository manually and do not open implementation pull requests here.

## Repository ownership

| | |
| --- | --- |
| **Use this repository for** | Reproducible Composer downloads and release provenance |
| **Use the canonical repository for** | Source, roadmap, architecture, issues, security reports, and contributions |
| **Publication rule** | Generated only from a completed, matching canonical release |
| **Application workflow** | Normal `composer.json`, `composer.lock`, and `vendor` managed through `pam composer` |
<!-- pam:distribution-page:end -->

Pam Native renders real Android views from persistent PHP. PHP owns application
state and events, Rust performs retained layout and incremental diffing, and
Kotlin mounts bounded mutation batches on the Android UI thread.

## First component

```php
final class Home extends \Pam\Native\Component
{
    #[\Pam\Native\Attributes\State]
    public int $count = 0;

    public function increment(): void
    {
        $this->count++;
    }
}
?>

<template>
<Screen>
    <SafeAreaView class="flex-1 surface">
        <Column class="flex-1 p-6 gap-4">
            <Text class="text-primary" fontSize="28">Hello, PHP</Text>
            <Button class="accent" height="52" @press="increment">
                Count: {{ $count }}
            </Button>
        </Column>
    </SafeAreaView>
</Screen>
</template>
```

## HTTP

`Http::get()` remains available for simple reads. Authenticated APIs can use the
generic request API or the `post()`, `put()`, `patch()` and `delete()` helpers:

```php
use Pam\Native\Http\Http;
use Pam\Native\Http\HttpResponse;
use Pam\Native\Http\OutboundTraceContext;

Http::json(
    method: 'POST',
    url: 'https://api.example.com/login',
    data: ['email' => $email, 'password' => $password],
    callback: function (HttpResponse $response): void {
        if ($response->transportFailed()) {
            // Status 0; inspect $response->error and keep offline work queued.
            return;
        }
        // Read $response->statusCode, $response->body and $response->successful().
    },
);

Http::request(
    method: 'PATCH',
    url: 'https://api.example.com/profile',
    callback: fn (HttpResponse $response) => $this->updated($response),
    headers: [
        'Authorization' => "Bearer {$token}",
        'Content-Type' => 'application/json',
    ],
    body: json_encode(['name' => $name], JSON_THROW_ON_ERROR),
);

$trace = new OutboundTraceContext(
    traceparent: $serverTraceparent,
    origin: 'https://api.example.com',
);
Http::get(
    'https://api.example.com/orders',
    fn (HttpResponse $response) => $this->loaded($response),
    trace: $trace,
);
```

Production builds require HTTPS. Requests support GET, POST, PUT, PATCH and
DELETE, up to 32 bounded single-line headers, a one MiB request body and timeout
values from one to 120 seconds. DNS, connectivity and timeout failures reach the
same callback with status `0`; they never crash the component runtime.
Distributed context uses a dedicated strict W3C version `00` value bound to one
exact HTTPS origin. Generic headers cannot set `traceparent` or `tracestate`,
and both native hosts revalidate the scope before transmitting the request.

## License

Free and open-source under the [Apache License 2.0](LICENSE). You may use,
modify, and distribute this package for any purpose, including commercially.
