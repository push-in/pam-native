<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\NotificationCredentials', \Pam\Native\Notifications\Contracts\NotificationCredentials::class, 'pushinbr/pam-native-notifications');
