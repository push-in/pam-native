<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

// Deprecated in 1.46; removed in 1.47.
MovedToPlugin::alias('Pam\\Native\\BiometricError', 'Pam\\Native\\Auth\\BiometricError', 'pushinbr/pam-native-auth');
