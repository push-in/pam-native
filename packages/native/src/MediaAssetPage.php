<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

// Deprecated in 1.46; removed in 1.47.
MovedToPlugin::alias('Pam\\Native\\MediaAssetPage', 'Pam\\Native\\Media\\MediaAssetPage', 'pushinbr/pam-native-media');
