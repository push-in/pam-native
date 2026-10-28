<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: AudioRecorder moved to the pushinbr/pam-native-audio plugin as
 * \Pam\Native\Audio\AudioRecorder. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\System\\AudioRecorder', 'Pam\\Native\\Audio\\AudioRecorder', 'pushinbr/pam-native-audio');
