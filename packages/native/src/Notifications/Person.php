<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\Person', \Pam\Native\Notifications\Contracts\Person::class, 'pushinbr/pam-native-notifications');
