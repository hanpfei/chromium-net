/* -*- Mode: C; indent-tabs-mode: t; c-basic-offset: 8; tab-width: 8 -*- */
/* gnome-keyring-memory.h - library for allocating memory that is non-pageable

   Copyright (C) 2007 Stefan Walter

   The Gnome Keyring Library is free software; you can redistribute it and/or
   modify it under the terms of the GNU Library General Public License as
   published by the Free Software Foundation; either version 2 of the
   License, or (at your option) any later version.

   The Gnome Keyring Library is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   Library General Public License for more details.

   You should have received a copy of the GNU Library General Public
   License along with the Gnome Library; see the file COPYING.LIB.  If not,
   write to the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
   Boston, MA 02111-1307, USA.

   Author: Stef Walter <stef@memberwebs.com>
*/

#ifndef GNOME_KEYRING_MEMORY_H
#define GNOME_KEYRING_MEMORY_H

#include <glib.h>

G_BEGIN_DECLS

/**
 * gnome_keyring_memory_new:
 * @type: The C type of the objects to allocate
 * @n_objects: The number of objects to allocate.
 *
 * Allocate objects in non-pageable gnome-keyring memory.
 *
 * Return value: The new block of memory.
 **/
#define gnome_keyring_memory_new(type, n_objects) \
	((type*)(gnome_keyring_memory_alloc (sizeof (type) * (n_objects))))

gpointer  gnome_keyring_memory_alloc          (gulong sz);

gpointer  gnome_keyring_memory_try_alloc      (gulong sz);

gpointer  gnome_keyring_memory_realloc        (gpointer p, gulong sz);

gpointer  gnome_keyring_memory_try_realloc    (gpointer p, gulong sz);

void      gnome_keyring_memory_free           (gpointer p);

gboolean  gnome_keyring_memory_is_secure      (gpointer p);

gchar*    gnome_keyring_memory_strdup         (const gchar* str);

G_END_DECLS

#endif /* GNOME_KEYRING_MEMORY_H */
