<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

/**
 * The audio and text tracks of a playing source. Each track is
 * `['id' => string, 'language' => string, 'label' => string, 'selected' => bool]`;
 * pass an `id` to MediaPlayer::audioTrack() or textTrack().
 */
final readonly class MediaTracksEvent
{
    /**
     * @param list<array{id: string, language: string, label: string, selected: bool}> $audio
     * @param list<array{id: string, language: string, label: string, selected: bool}> $text
     */
    public function __construct(
        public array $audio,
        public array $text,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);
        $tracks = json_decode(is_string($values['tracks'] ?? null) ? $values['tracks'] : '[]', true);
        $audio = [];
        $text = [];
        foreach (is_array($tracks) ? $tracks : [] as $track) {
            if (!is_array($track) || !is_string($track['id'] ?? null) || $track['id'] === '') {
                continue;
            }
            $entry = [
                'id' => $track['id'],
                'language' => is_string($track['language'] ?? null) ? $track['language'] : '',
                'label' => is_string($track['label'] ?? null) ? $track['label'] : '',
                'selected' => ($track['selected'] ?? false) === true,
            ];
            match ($track['type'] ?? null) {
                'audio' => $audio[] = $entry,
                'text' => $text[] = $entry,
                default => null,
            };
        }

        return new self($audio, $text);
    }
}
