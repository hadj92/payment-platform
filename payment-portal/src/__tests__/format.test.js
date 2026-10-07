import { describe, it, expect } from 'vitest'
import { formatAmount, availableActions } from '@/utils/format'

describe('formatAmount', () => {
  it('convertit les unites mineures en montant lisible', () => {
    expect(formatAmount(1050, 'EUR').replace(/ | /g, ' ')).toBe('10,50 €')
  })

  it("respecte les devises sans sous-unite", () => {
    // Le yen n'a pas de centimes : 1050 JPY, ce sont 1050 yens, pas 10,50.
    // Diviser systematiquement par 100 serait une erreur d'un facteur 100.
    expect(formatAmount(1050, 'JPY')).toContain('1')
    expect(formatAmount(1050, 'JPY')).not.toContain(',50')
  })

  it('gere le montant nul', () => {
    expect(formatAmount(0, 'EUR')).toContain('0')
  })
})

describe('availableActions', () => {
  it('un paiement autorise peut etre encaisse ou annule', () => {
    expect(availableActions({ status: 'AUTHORIZED' })).toEqual(['capture', 'cancel'])
  })

  it('un paiement encaisse ne peut plus etre annule, seulement rembourse', () => {
    // Nuance metier : annuler libere un hold, rembourser renvoie de l'argent deja
    // preleve. Les deux ne sont pas interchangeables.
    expect(availableActions({ status: 'CAPTURED' })).toEqual(['refund'])
  })

  it('un paiement refuse n\'autorise aucune operation', () => {
    expect(availableActions({ status: 'DECLINED' })).toEqual([])
  })

  it('un paiement partiellement encaisse peut etre complete ou rembourse', () => {
    expect(availableActions({ status: 'PARTIALLY_CAPTURED' })).toEqual(['capture', 'refund'])
  })
})
