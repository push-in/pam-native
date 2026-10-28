<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

MovedToPlugin::alias(ToastType::class, \Pam\Native\Toast\ToastType::class, 'pushinbr/pam-native-toast');
