<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use JsonException;
use LogicException;
use Pam\Native\AppState;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\MemoryPressure;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModuleTransport;
use Pam\Native\NativeOperation;
use Pam\Native\Renderable;
use Pam\Native\State;
use Pam\Native\Store\Stores;
use Pam\Native\Diagnostics\Profiler;
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Scheduling\TaskPriority;
use Pam\Native\System\IncomingShares;
use Pam\Native\System\Linking;
use Pam\Native\System\PushNotifications;
use Pam\Native\TemplateException;
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
            self::reportError($error);
        }
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

                $encoder = self::$encoder ??= new TreeEncoder();
                $encoded = Profiler::measure(
                    'php.encode',
                    static fn (): array => $encoder->encode($element),
                );
                self::$eventCallbacks = $encoded['callbacks'];
                $frame = $encoded['frame'];

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
                } finally {
                    ComponentLifecycle::finishRender();
                    PamPhpRegistry::finishRender();
                }
                ComponentLifecycle::commit();
            } while (self::$renderRequested);
        } finally {
            self::$rendering = false;
        }
    }

    public static function requestRender(): void
    {
        if (self::$root === null) {
            return;
        }
        if (self::$rendering) {
            self::$renderRequested = true;

            return;
        }
        Scheduler::schedule(
            static fn () => self::render(),
            TaskPriority::Render,
            'runtime.render',
        );
        Scheduler::drain();
    }

    public static function dispatchEvent(int $nodeId, int $eventKind, string $payload): void
    {
        try {
            if ($eventKind === EventKind::Back->value) {
                self::$backHandler?->__invoke();
                self::render();

                return;
            }
            if ($eventKind === EventKind::AppState->value) {
                $appState = AppState::from((int) $payload);
                ComponentLifecycle::appState($appState);
                self::$appStateHandler?->__invoke($appState);
                self::render();

                return;
            }
            if ($eventKind === EventKind::Dimensions->value) {
                $values = Wire::decodeMap($payload);
                self::$dimensionsHandler?->__invoke(new WindowMetrics(
                    width: (float) ($values['width'] ?? 0.0),
                    height: (float) ($values['height'] ?? 0.0),
                    density: (float) ($values['density'] ?? 1.0),
                    appearance: UserInterfaceAppearance::tryFrom(
                        (int) ($values['appearance'] ?? UserInterfaceAppearance::Light->value),
                    ) ?? UserInterfaceAppearance::Light,
                ));
                self::render();

                return;
            }
            if ($eventKind === EventKind::MemoryPressure->value) {
                self::$memoryPressureHandler?->__invoke(MemoryPressure::from((int) $payload));
                self::render();

                return;
            }

            $callback = self::$eventCallbacks[$nodeId.':'.$eventKind] ?? null;
            if ($callback === null) {
                return;
            }
            $callback($payload);
            self::render();
        } catch (Throwable $error) {
            self::reportError($error);
        }
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
            self::render();
        } catch (Throwable $error) {
            self::reportError($error);
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

    public static function reportError(Throwable $error): void
    {
        RuntimeSupervisor::failed($error);
        if (function_exists('pam_native_error')) {
            try {
                $template = $error instanceof TemplateException ? $error->template : null;
                $line = $error instanceof TemplateException ? $error->templateLine : $error->getLine();
                $column = $error instanceof TemplateException ? $error->templateColumn : 1;
                $payload = json_encode([
                    'version' => 1,
                    'type' => $error::class,
                    'message' => $error->getMessage(),
                    'file' => $template ?? $error->getFile(),
                    'line' => $line,
                    'column' => $column,
                    'trace' => substr($error->getTraceAsString(), 0, 12_000),
                ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
                pam_native_error("PAMERR1\n".$payload);
            } catch (JsonException) {
                pam_native_error($error::class.': '.$error->getMessage());
            }
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
        ComponentLifecycle::shutdown();
        PamPhpRegistry::releaseInstances();
        Stores::resetRuntime();
        Scheduler::reset();
        Profiler::reset();
        RuntimeSupervisor::reset();
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
