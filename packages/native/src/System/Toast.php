<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

MovedToPlugin::alias(Toast::class, \Pam\Native\Toast\Toast::class, 'pushinbr/pam-native-toast');
