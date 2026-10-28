<?php
declare(strict_types=1);
namespace Pam\Native\System;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\Biometrics', \Pam\Native\Auth\LegacyBiometrics::class, 'pushinbr/pam-native-auth');
