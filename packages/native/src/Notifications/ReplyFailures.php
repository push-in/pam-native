<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\ReplyFailures', \Pam\Native\Notifications\Contracts\ReplyFailures::class, 'pushinbr/pam-native-notifications');
