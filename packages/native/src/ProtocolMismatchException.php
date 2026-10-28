<?php

declare(strict_types=1);

namespace Pam\Native;

use RuntimeException;

/**
 * The installed renderer cannot display what the application sends: an
 * incompatible ABI or protocol version, or an extension property/event the
 * renderer does not implement. Thrown at boot or before the frame that would
 * carry the identifier, never after the renderer has received it.
 */
final class ProtocolMismatchException extends RuntimeException
{
}
