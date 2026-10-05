<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use JsonException;
use LogicException;
use Pam\Native\AppState;
use Pam\Native\App;
use Pam\Native\Appearance;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\MemoryPressure;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModuleTransport;
use Pam\Native\NativeOperation;
use Pam\Native\Renderable;
use Pam\Native\State;
use Pam\Native\Store\Stores;
use Pam\Native\Diagnostics\ErrorReporter;
use Pam\Native\Diagnostics\Profiler;
use Pam\Native\Diagnostics\RuntimeError;
use Pam\Native\Diagnostics\RuntimeErrorPhase;
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Scheduling\TaskPriority;
use Pam\Native\System\IncomingShares;
use Pam\Native\System\Linking;
use Pam\Native\System\PushNotifications;
use Pam\Native\TemplateException;
use Pam\Native\Theme;
use Pam\Native\Style\StyleVariables;
use Pam\Native\UserInterfaceAppearance;
use Pam\Native\WindowMetrics;
use Pam\Native\BuildConfiguration;
use Pam\Native\BuildMode;
use Throwable;

final class Runtime
{
    private static Renderable|Closure|null $root = null;

    /** @var array<string, Closure> */
    private static array $eventCallbacks = [];

    /** @var array<int, Closure> */
    private static array $moduleCallbacks = [];

    private static int $nextRequestId = 1;
    private static ?NativeModuleTransport $moduleTransport = null;
    private static ?string $lastFrame = null;
    private static ?Closure $backHandler = null;
    private static ?Closure $appStateHandler = null;
    private static ?Closure $dimensionsHandler = null;
    private static ?Closure $memoryPressureHandler = null;
    private static ?TreeEncoder $encoder = null;
    private static bool $rendering = false;
    private static bool $renderRequested = false;
    private static bool $dispatchingEvent = false;
    private static ?Throwable $renderFailure = null;
    private static WindowMetrics $windowMetrics;
    /** When true the native bridge drains its queue and calls flush() once. */
    private static bool $deferred = false;
    private static bool $pending = false;
    private static ?Element $lastRoot = null;
    private static ?Element $lastThemed = null;
    private static ?Theme $lastTheme = null;
    private static ?string $environmentKey = null;
    /** @var \WeakMap<Theme, \WeakMap<Element, Element>>|null */
    private static ?\WeakMap $themed = null;

    private function __construct()
    {
    }

    public static function boot(Renderable|Closure $root): void
    {
        if (self::$root !== null) {
            throw new LogicException('Pam Native is already running.');
        }

        self::$root = $root;
        self::$encoder = new TreeEncoder();
        Profiler::enabled(BuildConfiguration::mode() !== BuildMode::Production);
        try {
            self::render();
        } catch (Throwable $error) {
            self::reportError($error, RuntimeErrorPhase::Boot);
        }
    }

    public static function windowMetrics(): WindowMetrics
    {
        return self::$windowMetrics ??= self::bootMetrics();
    }

    /**
     * Restyles the tree for an appearance chosen in PHP (or reconciled from a
     * host event) without remounting it.
     */
    public static function replaceAppearance(UserInterfaceAppearance $appearance, bool $render = true): void
    {
        $metrics = self::windowMetrics();
        if ($metrics->appearance === $appearance) {
            return;
        }
        self::$windowMetrics = new WindowMetrics(
            width: $metrics->width,
            height: $metrics->height,
            density: $metrics->density,
            appearance: $appearance,
            fontScale: $metrics->fontScale,
            safeAreaTop: $metrics->safeAreaTop,
            safeAreaRight: $metrics->safeAreaRight,
            safeAreaBottom: $metrics->safeAreaBottom,
            safeAreaLeft: $metrics->safeAreaLeft,
            refreshRate: $metrics->refreshRate,
            reducedMotion: $metrics->reducedMotion,
            deviceType: $metrics->deviceType,
            pointer: $metrics->pointer,
            inputMode: $metrics->inputMode,
            dynamicRange: $metrics->dynamicRange,
            displayMode: $metrics->displayMode,
            foldPosture: $metrics->foldPosture,
            memoryClass: $metrics->memoryClass,
            performanceTier: $metrics->performanceTier,
        );
        DependencyTracker::invalidateAll();
        if ($render) {
            self::requestRender();
        }
    }

    /**
     * The host exports the effective appearance before PHP starts, so the
     * first frame already matches the native window instead of defaulting to
     * light and correcting itself after the first dimensions event.
     */
    private static function bootMetrics(): WindowMetrics
    {
        // Hosts export the window (size, density, safe areas) before PHP
        // starts so the very first render already sees the real geometry.
        $raw = getenv('PAM_BOOT_METRICS');
        $values = is_string($raw) && $raw !== '' && strlen($raw) < 4_096
            ? json_decode($raw, true)
            : null;
        if (!is_array($values)) {
            return new WindowMetrics(0.0, 0.0, 1.0, Appearance::bootAppearance());
        }
        $number = static function (string $key, float $default) use ($values): float {
            $value = $values[$key] ?? null;
            return (is_int($value) || is_float($value)) && is_finite((float) $value) && $value >= 0
                ? (float) $value
                : $default;
        };

        return new WindowMetrics(
            width: $number('width', 0.0),
            height: $number('height', 0.0),
            density: max(0.1, $number('density', 1.0)),
            appearance: Appearance::bootAppearance(),
            fontScale: max(0.1, $number('fontScale', 1.0)),
            safeAreaTop: $number('safeAreaTop', 0.0),
            safeAreaRight: $number('safeAreaRight', 0.0),
            safeAreaBottom: $number('safeAreaBottom', 0.0),
            safeAreaLeft: $number('safeAreaLeft', 0.0),
        );
    }

    public static function render(): void
    {
        if (self::$rendering) {
            self::$renderRequested = true;

            return;
        }
        $root = self::$root;

        if ($root === null) {
            throw new LogicException('Pam Native has not been booted.');
        }

        self::$rendering = true;
        $renderStarted = hrtime(true);
        try {
            do {
                self::$renderRequested = false;
                self::$pending = false;
                $theme = App::activeTheme();
                $environment = spl_object_id(self::windowMetrics()).':'
                    .StyleVariables::revision().':'
                    .($theme === null ? 0 : spl_object_id($theme));
                if ($environment !== self::$environmentKey) {
                    self::$environmentKey = $environment;
                    DependencyTracker::invalidateAll();
                }
                ComponentLifecycle::detectChanges();
                PamPhpRegistry::beginRender();
                ComponentLifecycle::beginRender();
                try {
                $element = Profiler::measure('php.render', static function () use ($root): ?Element {
                    $rendered = $root instanceof Renderable ? $root : $root();

                    return $rendered instanceof Renderable ? $rendered->toElement() : null;
                });

                if (!$element instanceof Element) {
                    throw new LogicException('The Pam Native root must be renderable.');
                }
                $untouched = $element;
                if ($theme !== null) {
                    $themed = self::$themed ??= new \WeakMap();
                    $memo = $themed[$theme] ?? null;
                    if ($memo === null) {
                        $memo = new \WeakMap();
                        $themed[$theme] = $memo;
                    }
                    $element = Profiler::measure(
                        'php.theme',
                        static fn (): Element => $theme->applyTo($element, $memo),
                    );
                }
                // When every component was memoized the committed tree is current.
                $unchanged = $untouched === self::$lastRoot
                    && $theme === self::$lastTheme
                    && $element === self::$lastThemed;

                $encoder = self::$encoder ??= new TreeEncoder();
                if (!$unchanged) {
                $encoded = Profiler::measure(
                    'php.encode',
                    static fn (): array => $encoder->encode($element),
                );
                self::$eventCallbacks = $encoded['callbacks'];
                $frame = $encoded['frame'];
                $current = true;

                if ($frame !== null) {
                    $committed = true;
                    if (function_exists('pam_native_commit')) {
                        $committed = pam_native_commit($frame);
                        if (!$committed && !$encoded['full']) {
                            $encoder->forceFullFrame();
                            $recovery = $encoder->encode($element);
                            self::$eventCallbacks = $recovery['callbacks'];
                            $frame = $recovery['frame'];
                            $committed = $frame !== null && pam_native_commit($frame);
                        }
                        if (!$committed) {
                            $current = false;
                            @file_put_contents(
                                sys_get_temp_dir() . '/pam-native-invalid-frame.bin',
                                $frame,
                            );
                        }
                    }
                    if ($committed) {
                        self::$lastFrame = $frame;
                        RuntimeSupervisor::committed(
                            $frame,
                            (hrtime(true) - $renderStarted) / 1_000_000,
                        );
                    }
                }
                if ($current) {
                    self::$lastRoot = $untouched;
                    self::$lastTheme = $theme;
                    self::$lastThemed = $element;
                }
                }
                } finally {
                    ComponentLifecycle::finishRender();
                    PamPhpRegistry::finishRender();
                }
                ComponentLifecycle::commit();
            } while (self::$renderRequested);
        } catch (Throwable $error) {
            self::$renderFailure = $error;

            throw $error;
        } finally {
            self::$rendering = false;
        }
    }

    /**
     * Requests a render after an untracked change: every memoized component
     * re-renders. Prefer scheduleRender() when the change already marked the
     * affected components dirty (tracked state, stores, signals).
     */
    public static function requestRender(): void
    {
        if (self::$root === null) {
            return;
        }
        DependencyTracker::invalidateAll();
        self::scheduleRender();
    }

    /** Requests a render after a tracked change; memoized components stay reused. */
    public static function scheduleRender(): void
    {
        if (self::$root === null) {
            return;
        }
        if (self::$dispatchingEvent) {
            // dispatchEvent() renders once after the callback. Coalesce state
            // mutations raised inside that callback instead of traversing the
            // tree immediately and then traversing it again.
            return;
        }
        if (self::$rendering) {
            self::$renderRequested = true;

            return;
        }
        if (self::$deferred) {
            self::$pending = true;

            return;
        }
        Scheduler::schedule(
            static fn () => self::render(),
            TaskPriority::Render,
            'runtime.render',
        );
        Scheduler::drain();
    }

    /**
     * Enables queue-drained rendering: events and module results only mark
     * the runtime dirty and the host calls flush() once its queue is empty
     * (or its frame budget elapsed), so bursts render once.
     */
    public static function deferRendering(bool $enabled = true): void
    {
        self::$deferred = $enabled;
        if (!$enabled) {
            self::flush();
        }
    }

    /** Renders once if anything was dispatched or scheduled since the last render. */
    public static function flush(): bool
    {
        if (!self::$pending || self::$root === null) {
            return false;
        }
        self::$pending = false;
        try {
            self::render();
        } catch (Throwable $error) {
            self::reportError($error, RuntimeErrorPhase::Render);
        }

        return true;
    }

    public static function hasPendingRender(): bool
    {
        return self::$pending;
    }

    private static function afterDispatch(): void
    {
        if (self::$deferred) {
            self::$pending = true;

            return;
        }
        self::render();
    }

    public static function dispatchEvent(int $nodeId, int $eventKind, string $payload): void
    {
        try {
            if ($eventKind === EventKind::Back->value) {
                DependencyTracker::invalidateAll();
                self::$backHandler?->__invoke();
                self::afterDispatch();

                return;
            }
            if ($eventKind === EventKind::AppState->value) {
                $appState = AppState::from((int) $payload);
                ComponentLifecycle::appState($appState);
                DependencyTracker::invalidateAll();
                self::$appStateHandler?->__invoke($appState);
                self::afterDispatch();

                return;
            }
            if ($eventKind === EventKind::Dimensions->value) {
                $values = Wire::decodeMap($payload);
                $previousAppearance = self::windowMetrics()->appearance;
                self::$windowMetrics = new WindowMetrics(
                    width: (float) ($values['width'] ?? 0.0),
                    height: (float) ($values['height'] ?? 0.0),
                    density: (float) ($values['density'] ?? 1.0),
                    appearance: UserInterfaceAppearance::tryFrom(
                        (int) ($values['appearance'] ?? UserInterfaceAppearance::Light->value),
                    ) ?? UserInterfaceAppearance::Light,
                    fontScale: (float) ($values['fontScale'] ?? 1.0),
                    safeAreaTop: (float) ($values['safeAreaTop'] ?? 0.0),
                    safeAreaRight: (float) ($values['safeAreaRight'] ?? 0.0),
                    safeAreaBottom: (float) ($values['safeAreaBottom'] ?? 0.0),
                    safeAreaLeft: (float) ($values['safeAreaLeft'] ?? 0.0),
                    refreshRate: (float) ($values['refreshRate'] ?? 60.0),
                    reducedMotion: (bool) ($values['reducedMotion'] ?? false),
                    deviceType: self::styleEnvironmentKeyword($values['deviceType'] ?? null, 'phone'),
                    pointer: self::styleEnvironmentKeyword($values['pointer'] ?? null, 'coarse'),
                    inputMode: self::styleEnvironmentKeyword($values['inputMode'] ?? null, 'touch'),
                    dynamicRange: self::styleEnvironmentKeyword($values['dynamicRange'] ?? null, 'standard'),
                    displayMode: self::styleEnvironmentKeyword($values['displayMode'] ?? null, 'standalone'),
                    foldPosture: self::styleEnvironmentKeyword($values['foldPosture'] ?? null, 'flat'),
                    memoryClass: (float) ($values['memoryClass'] ?? 0.0),
                    performanceTier: (float) ($values['performanceTier'] ?? 1.0),
                );
                Appearance::synchronize($values, $previousAppearance);
                DependencyTracker::invalidateAll();
                self::$dimensionsHandler?->__invoke(self::$windowMetrics);
                self::afterDispatch();

                return;
            }
            if ($eventKind === EventKind::MemoryPressure->value) {
                DependencyTracker::invalidateAll();
                self::$memoryPressureHandler?->__invoke(MemoryPressure::from((int) $payload));
                self::afterDispatch();

                return;
            }

            $callback = self::$eventCallbacks[$nodeId.':'.$eventKind] ?? null;
            if ($callback === null) {
                return;
            }
            if (!self::marksItsScope($callback)) {
                // Arbitrary element closures may mutate any state: re-render
                // everything. Template handlers mark their component dirty.
                DependencyTracker::invalidateAll();
            }
            self::$dispatchingEvent = true;
            try {
                $callback($payload);
            } finally {
                self::$dispatchingEvent = false;
            }
            self::afterDispatch();
        } catch (Throwable $error) {
            self::reportError($error, RuntimeErrorPhase::Event);
        }
    }

    private static function marksItsScope(Closure $callback): bool
    {
        return (new \ReflectionFunction($callback))->getClosureScopeClass()?->getName()
            === TemplateRenderer::class;
    }

    private static function styleEnvironmentKeyword(mixed $value, string $fallback): string
    {
        return is_string($value) && preg_match('/^[a-z][a-z0-9-]{0,31}$/D', $value) === 1
            ? $value
            : $fallback;
    }

    public static function dispatchModuleResult(
        int $requestId,
        int $status,
        string $payload,
    ): void {
        $callback = self::$moduleCallbacks[$requestId] ?? null;
        unset(self::$moduleCallbacks[$requestId]);

        if ($callback === null) {
            return;
        }

        try {
            $callback(ModuleResultStatus::from($status), $payload);
            self::afterDispatch();
        } catch (Throwable $error) {
            self::reportError($error, RuntimeErrorPhase::ModuleResult);
        }
    }

    public static function call(
        string $module,
        string $method,
        string $payload,
        Closure $callback,
    ): int {
        if (
            preg_match('/^[a-z0-9][a-z0-9._-]{0,63}$/', $module) !== 1
            || preg_match('/^[A-Za-z][A-Za-z0-9_]{0,63}$/', $method) !== 1
        ) {
            throw new LogicException('Native module and method names must be safe identifiers.');
        }

        $requestId = self::$nextRequestId++;
        self::$moduleCallbacks[$requestId] = $callback;

        if (self::$moduleTransport !== null) {
            self::$moduleTransport->invoke(
                $requestId,
                $module,
                $method,
                $payload,
                static fn (ModuleResultStatus $status, string $result): null => self::completeTransportCall(
                    $requestId,
                    $status,
                    $result,
                ),
            );
        } elseif (function_exists('pam_native_call')) {
            pam_native_call($requestId, $module, $method, $payload);
        }

        return $requestId;
    }

    public static function setModuleTransport(?NativeModuleTransport $transport): void
    {
        self::$moduleTransport = $transport;
    }

    private static function completeTransportCall(
        int $requestId,
        ModuleResultStatus $status,
        string $payload,
    ): null {
        self::dispatchModuleResult($requestId, $status->value, $payload);

        return null;
    }

    public static function callNative(
        NativeOperation $operation,
        string $payload,
        Closure $callback,
    ): int {
        $requestId = self::$nextRequestId++;
        self::$moduleCallbacks[$requestId] = $callback;

        if (function_exists('pam_native_call_typed')) {
            pam_native_call_typed($requestId, $operation->value, $payload);
        }

        return $requestId;
    }

    /**
     * Reports an uncaught error to App::onError() listeners and the native
     * host. Errors raised by a render pass are always reported as fatal
     * (Render) whatever path triggered that render.
     */
    public static function reportError(Throwable $error, ?RuntimeErrorPhase $phase = null): void
    {
        if ($error === self::$renderFailure) {
            $phase = $phase === RuntimeErrorPhase::Boot ? $phase : RuntimeErrorPhase::Render;
            self::$renderFailure = null;
        }
        $phase ??= RuntimeErrorPhase::Other;
        RuntimeSupervisor::failed($error);
        try {
            $report = RuntimeError::from(
                $error,
                $phase,
                withSnippet: BuildConfiguration::mode() !== BuildMode::Production,
            );
        } catch (Throwable) {
            $report = null;
        }
        if ($report !== null) {
            ErrorReporter::notify($error, $report);
        }
        if (!function_exists('pam_native_error')) {
            return;
        }
        try {
            $payload = json_encode([
                'version' => 2,
                ...($report?->toArray() ?? [
                    'type' => $error::class,
                    'message' => $error->getMessage(),
                    'file' => $error->getFile(),
                    'line' => $error->getLine(),
                    'column' => 1,
                    'phase' => $phase->value,
                    'fatal' => $phase->fatal(),
                ]),
                'trace' => substr($report?->traceAsString() ?? $error->getTraceAsString(), 0, 12_000),
            ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
            pam_native_error("PAMERR1\n".$payload);
        } catch (JsonException) {
            pam_native_error($error::class.': '.$error->getMessage());
        }
    }

    public static function shutdown(): void
    {
        self::$root = null;
        self::$eventCallbacks = [];
        self::$moduleCallbacks = [];
        self::$lastFrame = null;
        self::$nextRequestId = 1;
        self::$moduleTransport = null;
        self::$backHandler = null;
        self::$appStateHandler = null;
        self::$dimensionsHandler = null;
        self::$memoryPressureHandler = null;
        self::$encoder = null;
        self::$rendering = false;
        self::$renderRequested = false;
        self::$dispatchingEvent = false;
        self::$deferred = false;
        self::$pending = false;
        self::$lastRoot = null;
        self::$lastThemed = null;
        self::$lastTheme = null;
        self::$environmentKey = null;
        self::$themed = null;
        Appearance::resetRuntime();
        self::$windowMetrics = self::bootMetrics();
        ComponentLifecycle::shutdown();
        PamPhpRegistry::releaseInstances();
        Stores::resetRuntime();
        Scheduler::reset();
        Profiler::reset();
        RuntimeSupervisor::reset();
        ErrorReporter::reset();
        self::$renderFailure = null;
        Linking::resetRuntime();
        IncomingShares::resetRuntime();
        PushNotifications::resetRuntime();
        State::resetCache();
    }

    public static function lastFrame(): ?string
    {
        return self::$lastFrame;
    }

    public static function onBack(Closure $handler): void
    {
        self::$backHandler = $handler;
    }

    public static function onAppState(Closure $handler): void
    {
        self::$appStateHandler = $handler;
    }

    public static function onDimensions(Closure $handler): void
    {
        self::$dimensionsHandler = $handler;
    }

    public static function onMemoryPressure(Closure $handler): void
    {
        self::$memoryPressureHandler = $handler;
    }
}
