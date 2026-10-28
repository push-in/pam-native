<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

// Deprecated in 1.46; removed in 1.47.
MovedToPlugin::alias('Pam\\Native\\MediaAsset', 'Pam\\Native\\Media\\MediaAsset', 'pushinbr/pam-native-media');
