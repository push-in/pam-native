<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

// Deprecated in 1.46; removed in 1.47.
MovedToPlugin::alias('Pam\\Native\\System\\IncomingShares', 'Pam\\Native\\ShareExtension\\IncomingShares', 'pushinbr/pam-native-share-extension');
