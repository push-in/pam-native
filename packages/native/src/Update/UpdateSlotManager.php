<?php

declare(strict_types=1);

namespace Pam\Native\Update;

use RuntimeException;

final class UpdateSlotManager
{
    private const int MAX_BUNDLE_BYTES = 268_435_456;

    private readonly string $root;

    /** Uses the same private application directory consumed by both native hosts. */
    public static function forRuntime(): self
    {
        $state = getenv('PAM_NATIVE_STATE_DIR');
        if (!is_string($state) || $state === '' || str_contains($state, "\0")) {
            throw new RuntimeException('PAM Native state directory is unavailable.');
        }

        return new self(dirname($state).'/updates');
    }

    public function __construct(string $root)
    {
        if (str_contains($root, "\0") || (!is_dir($root) && !mkdir($root, 0o700, true) && !is_dir($root))) {
            throw new RuntimeException('Cannot create the update slot directory.');
        }
        $resolved = realpath($root);
        if (!is_string($resolved) || is_link($root)) {
            throw new RuntimeException('Update slot directory must be a real private directory.');
        }
        $this->root = $resolved;
    }

    public function stage(string $bundle, SignedUpdateManifest $manifest): UpdateActivationStatus
    {
        $size = is_file($bundle) ? filesize($bundle) : false;
        if (!is_int($size) || $size < 1 || $size > self::MAX_BUNDLE_BYTES || !UpdateVerifier::verifyBundle($bundle, $manifest)) {
            throw new RuntimeException('Update bundle failed size or integrity verification.');
        }
        return $this->locked(function () use ($bundle, $manifest): UpdateActivationStatus {
            $temporary = $this->root.'/candidate.'.bin2hex(random_bytes(8)).'.tmp';
            if (!copy($bundle, $temporary) || !chmod($temporary, 0o600)
                || !UpdateVerifier::verifyBundle($temporary, $manifest)
                || !rename($temporary, $this->path('candidate.bundle'))) {
                if (is_file($temporary)) {
                    unlink($temporary);
                }
                throw new RuntimeException('Cannot stage the verified update bundle.');
            }
            $this->writeMetadata('candidate.json', $manifest);
            return UpdateActivationStatus::Staged;
        });
    }

    public function activate(): UpdateActivationStatus
    {
        return $this->locked(function (): UpdateActivationStatus {
            $candidate = $this->path('candidate.bundle');
            if (!is_file($candidate) || !is_file($this->path('candidate.json'))) {
                throw new RuntimeException('No verified candidate update is staged.');
            }
            $active = $this->path('active.bundle');
            $previous = $this->path('previous.bundle');
            if (is_file($active) && !rename($active, $previous)) {
                throw new RuntimeException('Cannot preserve the active update for rollback.');
            }
            if (!rename($candidate, $active)) {
                if (is_file($previous)) {
                    rename($previous, $active);
                }
                throw new RuntimeException('Cannot activate the staged update.');
            }
            $candidateMetadata = $this->path('candidate.json');
            $activeMetadata = $this->path('active.json');
            if (is_file($activeMetadata)) {
                rename($activeMetadata, $this->path('previous.json'));
            }
            if (!rename($candidateMetadata, $activeMetadata)) {
                rename($active, $candidate);
                if (is_file($previous)) {
                    rename($previous, $active);
                }
                throw new RuntimeException('Cannot activate update metadata.');
            }
            return UpdateActivationStatus::Activated;
        });
    }

    public function rollback(): UpdateActivationStatus
    {
        return $this->locked(function (): UpdateActivationStatus {
            $previous = $this->path('previous.bundle');
            if (!is_file($previous)) {
                throw new RuntimeException('No previous update is available for rollback.');
            }
            $active = $this->path('active.bundle');
            $failed = $this->path('failed.bundle');
            if (is_file($active) && !rename($active, $failed)) {
                throw new RuntimeException('Cannot quarantine the failed update.');
            }
            if (!rename($previous, $active)) {
                if (is_file($failed)) {
                    rename($failed, $active);
                }
                throw new RuntimeException('Cannot roll back the previous update.');
            }
            $previousMetadata = $this->path('previous.json');
            if (is_file($previousMetadata)) {
                if (is_file($this->path('active.json'))) {
                    rename($this->path('active.json'), $this->path('failed.json'));
                }
                rename($previousMetadata, $this->path('active.json'));
            }
            return UpdateActivationStatus::RolledBack;
        });
    }

    public function activeBundle(): ?string
    {
        $path = $this->path('active.bundle');
        return is_file($path) ? $path : null;
    }

    private function path(string $name): string
    {
        return $this->root.'/'.$name;
    }

    private function writeMetadata(string $name, SignedUpdateManifest $manifest): void
    {
        $temporary = $this->path($name.'.tmp');
        if (file_put_contents($temporary, $manifest->canonicalJson(), LOCK_EX) === false
            || !chmod($temporary, 0o600) || !rename($temporary, $this->path($name))) {
            if (is_file($temporary)) {
                unlink($temporary);
            }
            throw new RuntimeException('Cannot publish update metadata.');
        }
    }

    private function locked(\Closure $operation): mixed
    {
        $lock = fopen($this->path('slots.lock'), 'c+b');
        if ($lock === false || !flock($lock, LOCK_EX)) {
            throw new RuntimeException('Cannot lock update slots.');
        }
        try {
            return $operation();
        } finally {
            flock($lock, LOCK_UN);
            fclose($lock);
        }
    }
}
