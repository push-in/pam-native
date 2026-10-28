<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: AudioRecordingProgress moved to the pushinbr/pam-native-audio plugin as
 * \Pam\Native\Audio\AudioRecordingProgress. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\AudioRecordingProgress', 'Pam\\Native\\Audio\\AudioRecordingProgress', 'pushinbr/pam-native-audio');
